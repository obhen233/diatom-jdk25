package com.github.obhen233.jdtls;

import com.github.obhen233.compiler.constant.Constants;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.*;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 使用 ECJ 内部编译器 API 提供编译诊断和代码补全能力。
 * 通过 FileSystem 加载 JDK 标准库，解决 java.lang.String 等类型无法解析的问题。
 */
public class JdtCoreService {

    private static final Logger log = LoggerFactory.getLogger(JdtCoreService.class);

    /** JDK classpath entries，启动时初始化一次 */
    private static final String[] JDK_CLASSPATH;

    /**
     * 从 JDK 扫描出的所有 public 类（延迟初始化）。
     * key = 简单类名 (e.g. "Date"), value = 完整类名列表 (e.g. ["java.util.Date", "java.sql.Date"])
     */
    private static volatile Map<String, List<String>> jdkClassIndex;

    static {
        JDK_CLASSPATH = buildJdkClasspath();
    }

    /** 延迟初始化 JDK 类索引，避免拖慢启动速度 */
    private static Map<String, List<String>> getJdkClassIndex() {
        if (jdkClassIndex == null) {
            synchronized (JdtCoreService.class) {
                if (jdkClassIndex == null) {
                    jdkClassIndex = buildClassIndex();
                }
            }
        }
        return jdkClassIndex;
    }

    /**
     * 构建 JDK classpath。
     * 支持 JDK 8 (rt.jar) 和 JDK 9+ (jrt-fs)。
     */
    private static String[] buildJdkClasspath() {
        List<String> paths = new ArrayList<>();
        String javaHome = System.getProperty("java.home");

        // JDK 8: java.home/lib/rt.jar
        File rtJar = new File(javaHome, "lib/rt.jar");
        if (rtJar.exists()) {
            paths.add(rtJar.getAbsolutePath());
            // 也加上 jce.jar, jsse.jar 等
            File libDir = new File(javaHome, "lib");
            for (File f : libDir.listFiles()) {
                if (f.getName().endsWith(".jar") && !f.getName().equals("rt.jar")) {
                    paths.add(f.getAbsolutePath());
                }
            }
        } else {
            // JDK 9+: 使用 jrt-fs
            File jrtFs = new File(javaHome, "lib/jrt-fs.jar");
            if (jrtFs.exists()) {
                paths.add(jrtFs.getAbsolutePath());
            }
            // 也尝试 ct.sym
            File ctSym = new File(javaHome, "lib/ct.sym");
            if (ctSym.exists()) {
                paths.add(ctSym.getAbsolutePath());
            }
        }

        log.info("[JDT] java.home = {}", javaHome);
        log.info("[JDT] classpath entries = {}", paths);
        return paths.toArray(new String[0]);
    }

    /**
     * 扫描 JDK 中所有 public 类，构建 简单类名 -> 完整类名 的索引。
     * JDK 8: 扫描 rt.jar
     * JDK 9+: 扫描 jrt:/ 文件系统
     */
    private static Map<String, List<String>> buildClassIndex() {
        Map<String, List<String>> index = new HashMap<>();
        String javaHome = System.getProperty("java.home");
        long start = System.currentTimeMillis();

        File rtJar = new File(javaHome, "lib/rt.jar");
        if (rtJar.exists()) {
            // JDK 8: 扫描 rt.jar
            scanJar(rtJar, index);
            // 也扫描其他常用 jar
            File libDir = new File(javaHome, "lib");
            File[] files = libDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".jar") && !f.getName().equals("rt.jar")) {
                        scanJar(f, index);
                    }
                }
            }
        } else {
            // JDK 9+: 扫描 jrt:/ 文件系统
            scanJrtFileSystem(index);
        }

        log.info("[JDT] class index built: {} unique class names in {}ms", index.size(), System.currentTimeMillis() - start);
        return index;
    }

    /**
     * 扫描 jar 文件中的 .class 文件，提取 public 类。
     */
    private static void scanJar(File jarFile, Map<String, List<String>> index) {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.contains("$")) {
                    // com/example/Foo.class -> com.example.Foo
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    // 跳过内部包（sun.*, com.sun.* 等）
                    if (isPublicApiPackage(className)) {
                        addToIndex(index, className);
                    }
                }
            }
        } catch (IOException e) {
            log.error("[JDT] Failed to scan jar: {} - {}", jarFile, e.getMessage());
        }
    }

    /**
     * JDK 9+: 扫描 jrt:/ 文件系统。
     */
    private static void scanJrtFileSystem(Map<String, List<String>> index) {
        try {

            java.nio.file.FileSystem jrtFs = FileSystems.newFileSystem(URI.create("jrt:/"), Collections.emptyMap());
            Path modulesPath = jrtFs.getPath("/modules");
            try (Stream<Path> walk = Files.walk(modulesPath)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        // /modules/java.base/java/util/Date.class
                        String full = p.toString();
                        // 去掉 /modules/<module>/ 前缀
                        int thirdSlash = full.indexOf('/', full.indexOf('/', 1) + 1);
                        if (thirdSlash < 0) return;
                        String relative = full.substring(thirdSlash + 1);
                        if (relative.contains("$")) return;
                        String className = relative.substring(0, relative.length() - 6).replace('/', '.');
                        if (isPublicApiPackage(className)) {
                            addToIndex(index, className);
                        }
                    });
            }
        } catch (Exception e) {
            log.error("[JDT] Failed to scan jrt filesystem: {}", e.getMessage());
        }
    }

    /**
     * 判断是否为公开 API 包（过滤掉 sun.*, com.sun.internal.* 等内部包）。
     */
    private static boolean isPublicApiPackage(String fullClassName) {
        return fullClassName.startsWith("java.")
                || fullClassName.startsWith("javax.")
                || fullClassName.startsWith("org.w3c.")
                || fullClassName.startsWith("org.xml.")
                || fullClassName.startsWith("org.ietf.");
    }

    private static void addToIndex(Map<String, List<String>> index, String fullClassName) {
        int lastDot = fullClassName.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? fullClassName.substring(lastDot + 1) : fullClassName;
        // 跳过 package-info 等特殊文件
        if (simpleName.isEmpty() || simpleName.equals("package-info") || simpleName.equals("module-info")) return;
        // 避免重复添加
        List<String> list = index.computeIfAbsent(simpleName, k -> new ArrayList<>());
        if (!list.contains(fullClassName)) {
            list.add(fullClassName);
        }
    }

    /**
     * 创建能解析 JDK 类型的 NameEnvironment。
     */
    private INameEnvironment createNameEnvironment() {
        return new FileSystem(JDK_CLASSPATH, new String[]{}, "UTF-8");
    }

    /**
     * 创建包含项目依赖的 NameEnvironment。
     * classpath = JDK + 项目 lib/*.jar
     * sourcepath = 项目源码根目录（src/, src/main/java/ 等），使 ECJ 能跨包解析 .java 文件
     */
    private INameEnvironment createNameEnvironment(String[] projectClasspath) {
        return createNameEnvironment(projectClasspath, null);
    }

    /**
     * 创建包含项目依赖和打开文档的 NameEnvironment。
     * 
     * @param projectClasspath 项目 classpath
     * @param openDocuments 打开的文档 (URI -> 内容)，用于解析跨包引用
     */
    private INameEnvironment createNameEnvironment(String[] projectClasspath, Map<String, String> openDocuments) {
        if (projectClasspath == null || projectClasspath.length == 0) {
            return createNameEnvironment();
        }
        // 分离 classpath 和 sourcepath
        List<String> cpList = new ArrayList<>();
        List<String> spList = new ArrayList<>();
        Collections.addAll(cpList, JDK_CLASSPATH);
        for (String path : projectClasspath) {
            java.io.File f = new java.io.File(path);
            if (f.isDirectory()) {
                // 目录既加入 classpath（找 .class）也加入 sourcepath（找 .java）
                cpList.add(path);
                spList.add(path);
            } else {
                // jar 文件只加入 classpath
                cpList.add(path);
            }
        }
        
        // 如果有打开的文档，创建自定义的 NameEnvironment
        if (openDocuments != null && !openDocuments.isEmpty()) {
            return new InMemoryNameEnvironment(
                cpList.toArray(new String[0]),
                spList.toArray(new String[0]),
                openDocuments
            );
        }
        
        return new FileSystem(
            cpList.toArray(new String[0]),
            spList.toArray(new String[0]),
            "UTF-8"
        );
    }

    private CompilerOptions getCompilerOptions() {
        // 按 Constants.jdkVersion（由 ide.jdk.version 设置同步）动态设置编译目标版本，默认 25。
        // ECJ 的 -source/-target 格式：JDK 8- 为 "1.8"，JDK 9+ 为 "9"。
        int jdkVersion = Constants.jdkVersion;
        String version = jdkVersion <= 8 ? "1." + jdkVersion : String.valueOf(jdkVersion);

        Map<String, String> settings = new HashMap<>();
        settings.put(CompilerOptions.OPTION_Source, version);
        settings.put(CompilerOptions.OPTION_Compliance, version);
        settings.put(CompilerOptions.OPTION_TargetPlatform, version);
        return new CompilerOptions(settings);
    }

    /**
     * 解析 Java 源码，返回编译问题列表（错误 + 警告）。
     */
    public List<IProblem> diagnose(String source, String unitName) {
        return diagnose(source, unitName, null);
    }

    /**
     * 解析 Java 源码，返回编译问题列表（错误 + 警告），支持项目级 classpath。
     */
    public List<IProblem> diagnose(String source, String unitName, String[] projectClasspath) {
        return diagnose(source, unitName, projectClasspath, null);
    }

    /**
     * 解析 Java 源码，返回编译问题列表（错误 + 警告），支持项目级 classpath 和打开的文档。
     * 
     * @param source 当前文档的源码
     * @param unitName 编译单元名称（相对于源码根的路径）
     * @param projectClasspath 项目 classpath
     * @param openDocuments 所有打开的文档 (URI -> 内容)，用于解析跨包引用
     */
    public List<IProblem> diagnose(String source, String unitName, String[] projectClasspath, 
                                    Map<String, String> openDocuments) {
        List<IProblem> result = new ArrayList<>();

        ICompilationUnit sourceUnit = new CompilationUnit(
                source.toCharArray(),
                unitName.endsWith(".java") ? unitName : unitName + ".java",
                "UTF-8"
        );

        INameEnvironment environment = createNameEnvironment(projectClasspath, openDocuments);
        CompilerOptions options = getCompilerOptions();

        IErrorHandlingPolicy policy = DefaultErrorHandlingPolicies.proceedWithAllProblems();
        DefaultProblemFactory problemFactory = new DefaultProblemFactory(Locale.getDefault());

        ICompilerRequestor requestor = new ICompilerRequestor() {
            @Override
            public void acceptResult(CompilationResult compilationResult) {
                if (compilationResult.hasProblems()) {
                    Collections.addAll(result, compilationResult.getProblems());
                }
            }
        };

        Compiler compiler = new Compiler(
                environment, policy, options, requestor, problemFactory
        );
        compiler.compile(new ICompilationUnit[]{sourceUnit});

        return result;
    }

    /**
     * 根据光标偏移量提供代码补全建议。
     * 支持：类名补全（带 import）、点号后方法补全、关键字、标识符。
     */
    public List<CompletionProposal> complete(String source, String unitName, int offset) {
        return complete(source, unitName, offset, null);
    }

    /**
     * 根据光标偏移量提供代码补全建议，支持项目级 classpath。
     */
    public List<CompletionProposal> complete(String source, String unitName, int offset, String[] projectClasspath) {
        List<CompletionProposal> proposals = new ArrayList<>();

        // 构建项目级类索引（JDK + 项目依赖）
        Map<String, List<String>> classIndex = buildProjectClassIndex(projectClasspath);

        // 检查是否是点号补全 (e.g. "sdf." or "sdf.fo")
        DotContext dotCtx = parseDotContext(source, offset);
        if (dotCtx != null) {
            addMethodProposals(proposals, dotCtx, source, projectClasspath, classIndex);
            return proposals;
        }

        String prefix = getPrefix(source, offset);
        if (prefix.isEmpty()) return proposals;

        // 1. 收集源码中出现的标识符
        collectIdentifiers(source, prefix, proposals);

        // 2. Java 关键字
        addKeywordProposals(proposals, prefix);

        // 3. JDK + 项目依赖类（带 import 信息，自动去重）
        addClassProposals(proposals, prefix, source, classIndex);

        return proposals;
    }

    /** 项目级类索引缓存，key = 项目名 */
    private static final Map<String, Map<String, List<String>>> PROJECT_INDEX_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 刷新指定项目的类索引缓存（当新文件创建时调用）
     */
    public void refreshProjectIndex(String projectName) {
        if (projectName == null || projectName.isEmpty()) return;
        PROJECT_INDEX_CACHE.remove(projectName);
        log.info("[JDT] Refreshed class index for project: {}", projectName);
    }

    /**
     * 构建项目级类索引：JDK 类 + 项目 jar 中的类 + 项目源码中的类。
     */
    private Map<String, List<String>> buildProjectClassIndex(String[] projectClasspath) {
        if (projectClasspath == null || projectClasspath.length == 0) {
            return getJdkClassIndex();
        }
        
        // 尝试从缓存中获取（基于 classpath 路径生成缓存 key）
        String cacheKey = String.join("|", projectClasspath);
        
        // 合并 JDK 索引 + 项目 jar 索引 + 项目源码索引
        Map<String, List<String>> merged = new HashMap<>();
        for (Map.Entry<String, List<String>> e : getJdkClassIndex().entrySet()) {
            merged.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        for (String path : projectClasspath) {
            File f = new File(path);
            if (f.isFile() && f.getName().endsWith(".jar")) {
                scanJarAllClasses(f, merged);
            } else if (f.isDirectory()) {
                scanClassDir(f, f, merged);
                // 也扫描 .java 源文件，提取类名
                scanSourceDir(f, f, merged);
            }
        }
        return merged;
    }

    /**
     * 扫描源码目录中的 .java 文件，根据目录结构推断全限定类名。
     * 例如 baseDir/com/sunway/test2/Hello.java → com.sunway.test2.Hello
     * 
     * 改进：支持从 package 声明中提取类名，更准确地识别跨包引用
     */
    private static void scanSourceDir(File baseDir, File dir, Map<String, List<String>> index) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanSourceDir(baseDir, f, index);
            } else if (f.getName().endsWith(".java") && !f.getName().contains("$")) {
                String simpleClassName = f.getName().replace(".java", "");
                boolean addedFromPackage = false;
                
                // 优先从文件内容的 package 声明中提取（更准确，避免路径推断出错）
                try {
                    String content = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
                    String pkgName = extractPackageFromSource(content);
                    if (pkgName != null && !pkgName.isEmpty()) {
                        String fullClassName = pkgName + "." + simpleClassName;
                        addToIndex(index, fullClassName);
                        addedFromPackage = true;
                    } else {
                        // 默认包
                        addToIndex(index, simpleClassName);
                        addedFromPackage = true;
                    }
                } catch (Exception e) {
                    // 读取失败，回退到路径推断
                }
                
                // 仅当 package 声明提取失败时，才用文件路径推断
                if (!addedFromPackage) {
                    String rel = baseDir.toPath().relativize(f.toPath()).toString();
                    String className = rel.substring(0, rel.length() - 5) // 去掉 .java
                            .replace(File.separatorChar, '.').replace('/', '.');
                    addToIndex(index, className);
                }
            }
        }
    }
    
    /**
     * 从源码中提取 package 声明
     */
    private static String extractPackageFromSource(String source) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^\\s*package\\s+([\\w.]+)\\s*;", java.util.regex.Pattern.MULTILINE)
            .matcher(source);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 公开方法：查找指定简单类名的所有全限定名候选（用于自动 import）。
     * 同时从磁盘索引和内存中打开的文档中查找。
     */
    public List<String> findImportCandidates(String simpleName, String[] projectClasspath) {
        return findImportCandidates(simpleName, projectClasspath, null);
    }

    public List<String> findImportCandidates(String simpleName, String[] projectClasspath, Map<String, String> openDocuments) {
        Map<String, List<String>> classIndex = buildProjectClassIndex(projectClasspath);

        // 也从内存中打开的文档提取候选
        if (openDocuments != null) {
            for (Map.Entry<String, String> entry : openDocuments.entrySet()) {
                String content = entry.getValue();
                String pkgName = extractPackageFromSource(content);
                String uri = entry.getKey();
                // 从 URI 提取文件名
                int lastSlash = uri.lastIndexOf('/');
                String fileName = lastSlash >= 0 ? uri.substring(lastSlash + 1) : uri;
                if (!fileName.endsWith(".java")) continue;
                String clsName = fileName.replace(".java", "");
                if (pkgName != null && !pkgName.isEmpty()) {
                    String fullName = pkgName + "." + clsName;
                    List<String> list = classIndex.computeIfAbsent(clsName, k -> new ArrayList<>());
                    if (!list.contains(fullName)) list.add(fullName);
                }
            }
        }

        List<String> candidates = classIndex.get(simpleName);
        return candidates != null ? candidates : Collections.<String>emptyList();
    }

    /**
     * 扫描 jar 中所有 public 类（不限于 JDK 公开包）。
     */
    private static void scanJarAllClasses(File jarFile, Map<String, List<String>> index) {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.contains("$")) {
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    addToIndex(index, className);
                }
            }
        } catch (IOException e) {
            log.error("[JDT] Failed to scan project jar: {} - {}", jarFile, e.getMessage());
        }
    }

    /**
     * 扫描已编译的 class 目录。
     */
    private static void scanClassDir(File baseDir, File dir, Map<String, List<String>> index) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanClassDir(baseDir, f, index);
            } else if (f.getName().endsWith(".class") && !f.getName().contains("$")) {
                String rel = baseDir.toPath().relativize(f.toPath()).toString();
                String className = rel.substring(0, rel.length() - 6)
                        .replace(File.separatorChar, '.').replace('/', '.');
                addToIndex(index, className);
            }
        }
    }

    // ==================== 点号补全：变量.方法 ====================

    /**
     * 解析点号上下文：光标前是否有 "变量名." 模式。
     */
    private DotContext parseDotContext(String source, int offset) {
        if (offset <= 0 || offset > source.length()) return null;

        // 获取光标后的方法前缀 (e.g. "sdf.fo|" -> methodPrefix="fo")
        String methodPrefix = "";
        int pos = offset - 1;
        while (pos >= 0 && Character.isJavaIdentifierPart(source.charAt(pos))) {
            pos--;
        }
        if (pos < 0 || source.charAt(pos) != '.') return null;

        methodPrefix = source.substring(pos + 1, offset);
        int dotPos = pos;

        // 获取点号前的变量名 (e.g. "sdf.fo" -> varName="sdf")
        pos = dotPos - 1;
        while (pos >= 0 && Character.isJavaIdentifierPart(source.charAt(pos))) {
            pos--;
        }
        String varName = source.substring(pos + 1, dotPos);
        if (varName.isEmpty()) return null;

        // 从源码中查找变量的类型
        String typeName = resolveVariableType(source, varName);
        if (typeName == null) return null;

        return new DotContext(varName, typeName, methodPrefix);
    }

    /**
     * 从源码中解析变量的类型声明。
     * 支持: "Type varName", "Type varName = ...", "for(Type varName : ...)"
     */
    private String resolveVariableType(String source, String varName) {
        // 匹配 "TypeName varName" 模式（含泛型）
        Pattern p = Pattern.compile("([A-Z][\\w<>\\[\\],\\s]*?)\\s+" + Pattern.quote(varName) + "\\s*[=;,):]");
        Matcher m = p.matcher(source);
        if (m.find()) {
            String rawType = m.group(1).trim();
            // 去掉泛型部分: ArrayList<String> -> ArrayList
            int lt = rawType.indexOf('<');
            return lt > 0 ? rawType.substring(0, lt) : rawType;
        }
        return null;
    }

    /**
     * 根据类型名查找类的 public 方法，添加到补全列表。
     */
    private void addMethodProposals(List<CompletionProposal> proposals, DotContext ctx, String source,
                                     String[] projectClasspath, Map<String, List<String>> classIndex) {
        // 先从 import 和 java.lang 中解析完整类名
        String fullClassName = resolveFullClassName(ctx.typeName, source, classIndex);
        if (fullClassName == null) return;

        try {
            Class<?> clazz;
            if (projectClasspath != null && projectClasspath.length > 0) {
                // 使用项目 classpath 加载类
                URL[] urls = new URL[projectClasspath.length];
                for (int i = 0; i < projectClasspath.length; i++) {
                    urls[i] = new File(projectClasspath[i]).toURI().toURL();
                }
                URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader());
                clazz = loader.loadClass(fullClassName);
            } else {
                clazz = Class.forName(fullClassName);
            }
            Set<String> seen = new HashSet<>();
            String prefix = ctx.methodPrefix.toLowerCase();

            for (Method method : clazz.getMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) continue;
                String name = method.getName();
                if (!name.toLowerCase().startsWith(prefix)) continue;
                if (seen.contains(name)) continue; // 跳过重载，只显示一次方法名
                seen.add(name);

                // 构建参数签名
                StringBuilder sig = new StringBuilder(name + "(");
                Class<?>[] paramTypes = method.getParameterTypes();
                for (int i = 0; i < paramTypes.length; i++) {
                    if (i > 0) sig.append(", ");
                    sig.append(paramTypes[i].getSimpleName());
                }
                sig.append(")");
                String returnType = method.getReturnType().getSimpleName();
                String detail = sig.toString() + " : " + returnType;

                proposals.add(new CompletionProposal(
                        name, CompletionProposal.Kind.METHOD, name, detail, null));
            }
        } catch (ClassNotFoundException e) {
            log.error("[JDT] Class not found for method completion: {}", fullClassName);
        } catch (Exception e) {
            log.error("[JDT] Error loading class for method completion: {} - {}", fullClassName, e.getMessage());
        }
    }

    /**
     * 根据简单类名和源码中的 import 语句，解析完整类名。
     */
    private String resolveFullClassName(String simpleName, String source, Map<String, List<String>> classIndex) {
        // 1. java.lang 包的类
        try {
            Class.forName("java.lang." + simpleName);
            return "java.lang." + simpleName;
        } catch (ClassNotFoundException ignored) {}

        // 2. 从 import 语句中查找
        Pattern importPattern = Pattern.compile("import\\s+([\\w.]+\\." + Pattern.quote(simpleName) + ")\\s*;");
        Matcher m = importPattern.matcher(source);
        if (m.find()) {
            return m.group(1);
        }

        // 3. 从类索引中查找（JDK + 项目依赖，如果只有一个匹配）
        List<String> candidates = classIndex.get(simpleName);
        if (candidates != null && candidates.size() == 1) {
            return candidates.get(0);
        }

        return null;
    }

    private static class DotContext {
        final String varName;
        final String typeName;
        final String methodPrefix;

        DotContext(String varName, String typeName, String methodPrefix) {
            this.varName = varName;
            this.typeName = typeName;
            this.methodPrefix = methodPrefix;
        }
    }

    // ==================== 类名补全（带 import 去重） ====================

    /**
     * 从类索引中动态查找匹配前缀的类，附带 import 信息。
     */
    private void addClassProposals(List<CompletionProposal> proposals, String prefix, String source,
                                   Map<String, List<String>> classIndex) {
        int count = 0;
        int maxResults = 50;

        // 预先解析源码中已有的 import 语句: simpleName -> (fullName, lineIndex)
        Map<String, ImportInfo> existingImports = parseExistingImports(source);

        for (Map.Entry<String, List<String>> entry : classIndex.entrySet()) {
            String simpleName = entry.getKey();
            if (!simpleName.startsWith(prefix)) continue;

            for (String fullName : entry.getValue()) {
                if (count >= maxResults) return;

                boolean isJavaLang = fullName.startsWith("java.lang.");
                boolean alreadyImported = isJavaLang || source.contains("import " + fullName + ";");

                String detail;
                CompletionProposal.ImportEdit importEdit = null;

                if (alreadyImported) {
                    detail = fullName;
                } else {
                    ImportInfo existing = existingImports.get(simpleName);
                    if (existing != null && !existing.fullName.equals(fullName)) {
                        // 已有同名但不同包的 import -> 替换
                        detail = fullName + " (替换 " + existing.fullName + ")";
                        importEdit = new CompletionProposal.ImportEdit(
                                "import " + fullName + ";\n",
                                existing.lineIndex,
                                existing.lineIndex + 1,
                                true // isReplace
                        );
                    } else if (existing == null) {
                        // 没有同名 import -> 新增
                        detail = fullName + " (需要 import)";
                        importEdit = new CompletionProposal.ImportEdit(
                                "import " + fullName + ";\n",
                                -1, -1, false
                        );
                    } else {
                        // 已有完全相同的 import
                        detail = fullName;
                    }
                }

                proposals.add(new CompletionProposal(
                        simpleName, CompletionProposal.Kind.CLASS, simpleName, detail, importEdit));
                count++;
            }
        }
    }

    /**
     * 解析源码中已有的 import 语句。
     */
    private Map<String, ImportInfo> parseExistingImports(String source) {
        Map<String, ImportInfo> imports = new HashMap<>();
        String[] lines = source.split("\n");
        Pattern importPattern = Pattern.compile("^\\s*import\\s+([\\w.]+)\\s*;");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = importPattern.matcher(lines[i]);
            if (m.find()) {
                String fullName = m.group(1);
                int lastDot = fullName.lastIndexOf('.');
                String simpleName = lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
                imports.put(simpleName, new ImportInfo(fullName, i));
            }
        }
        return imports;
    }

    private static class ImportInfo {
        final String fullName;
        final int lineIndex; // 0-based line number in source
        ImportInfo(String fullName, int lineIndex) {
            this.fullName = fullName;
            this.lineIndex = lineIndex;
        }
    }

    private void collectIdentifiers(String source, String prefix, List<CompletionProposal> proposals) {
        Set<String> seen = new HashSet<>();
        int i = 0;
        while (i < source.length()) {
            if (Character.isJavaIdentifierStart(source.charAt(i))) {
                int start = i;
                while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) {
                    i++;
                }
                String word = source.substring(start, i);
                if (word.startsWith(prefix) && !word.equals(prefix) && !seen.contains(word)) {
                    seen.add(word);
                    proposals.add(new CompletionProposal(word, CompletionProposal.Kind.VARIABLE, word));
                }
            } else {
                i++;
            }
        }
    }

    private void addKeywordProposals(List<CompletionProposal> proposals, String prefix) {
        String[] keywords = {
                "abstract", "assert", "boolean", "break", "byte", "case", "catch",
                "char", "class", "continue", "default", "do", "double", "else",
                "enum", "extends", "final", "finally", "float", "for", "if",
                "implements", "import", "instanceof", "int", "interface", "long",
                "new", "package", "private", "protected", "public", "return",
                "short", "static", "super", "switch", "synchronized", "this",
                "throw", "throws", "try", "void", "volatile", "while",
                "String", "System"
        };
        for (String kw : keywords) {
            if (kw.startsWith(prefix)) {
                proposals.add(new CompletionProposal(kw, CompletionProposal.Kind.KEYWORD, kw));
            }
        }
    }

    private String getPrefix(String source, int offset) {
        if (offset <= 0 || offset > source.length()) return "";
        int start = offset - 1;
        while (start >= 0 && Character.isJavaIdentifierPart(source.charAt(start))) {
            start--;
        }
        return source.substring(start + 1, offset);
    }

    // ==================== 自动生成未实现方法 ====================

    /**
     * 分析源码，生成需要实现的抽象方法/接口方法的代码。
     * 支持：
     * 1. class Foo implements Runnable { } → 生成 run() 方法
     * 2. class Foo extends AbstractList { } → 生成 get(), size() 等
     * 3. new Runnable() { } → 匿名内部类生成方法
     * 4. Runnable r = () -> { }; → Lambda 提示
     *
     * @return 方法存根代码列表，每个元素包含插入位置和代码文本
     */
    public List<MethodStub> generateMethodStubs(String source, String[] projectClasspath) {
        List<MethodStub> stubs = new ArrayList<>();
        ClassLoader loader = buildClassLoader(projectClasspath);

        // 1. 解析 class ... implements/extends 声明
        generateClassStubs(source, loader, stubs);

        // 2. 解析匿名内部类 new Interface() { }
        generateAnonymousClassStubs(source, loader, stubs);

        return stubs;
    }

    /**
     * 解析类声明中的 implements/extends，生成未实现的方法。
     */
    private void generateClassStubs(String source, ClassLoader loader, List<MethodStub> stubs) {
        // 匹配 class ClassName extends/implements TypeName
        Pattern classPattern = Pattern.compile(
                "class\\s+(\\w+)\\s+(?:extends\\s+(\\w+)\\s*)?(?:implements\\s+([\\w,\\s]+))?\\s*\\{");
        Matcher m = classPattern.matcher(source);
        while (m.find()) {
            String className = m.group(1);
            String extendsType = m.group(2);
            String implementsTypes = m.group(3);

            // 找到类体的插入位置（第一个 { 之后）
            int bracePos = m.end() - 1;
            int insertOffset = findInsertPositionInClassBody(source, bracePos);

            Set<String> existingMethods = collectExistingMethods(source, bracePos);
            List<Method> abstractMethods = new ArrayList<>();
            List<SourceMethodInfo> sourceAbstractMethods = new ArrayList<>();

            // 收集 extends 的抽象方法
            if (extendsType != null) {
                Class<?> parentClass = resolveClass(extendsType, source, loader);
                if (parentClass != null) {
                    collectAbstractMethods(parentClass, abstractMethods);
                } else {
                    // 回退：从项目源码中解析
                    sourceAbstractMethods.addAll(resolveMethodsFromProjectSource(extendsType, source));
                }
            }

            // 收集 implements 的接口方法
            if (implementsTypes != null) {
                for (String iface : implementsTypes.split(",")) {
                    String ifaceName = iface.trim();
                    if (ifaceName.isEmpty()) continue;
                    Class<?> ifaceClass = resolveClass(ifaceName, source, loader);
                    if (ifaceClass != null) {
                        collectAbstractMethods(ifaceClass, abstractMethods);
                    } else {
                        // 回退：从项目源码中解析
                        sourceAbstractMethods.addAll(resolveMethodsFromProjectSource(ifaceName, source));
                    }
                }
            }

            // 过滤掉已经实现的方法，生成代码
            String indent = detectIndent(source, bracePos);
            StringBuilder sb = new StringBuilder();

            // 从 Class 反射得到的方法
            for (Method method : abstractMethods) {
                String methodSig = method.getName() + "(" + getParamTypeSignature(method) + ")";
                if (existingMethods.contains(methodSig) || existingMethods.contains(method.getName())) continue;
                sb.append(generateMethodCode(method, indent));
            }

            // 从源码解析得到的方法（回退方案）
            for (SourceMethodInfo smi : sourceAbstractMethods) {
                if (existingMethods.contains(smi.methodName)) continue;
                sb.append(smi.generateCode(indent));
            }

            if (sb.length() > 0) {
                stubs.add(new MethodStub(insertOffset, sb.toString(), className));
            }
        }
    }

    /**
     * 解析匿名内部类 new TypeName() { }，生成需要实现的方法。
     */
    private void generateAnonymousClassStubs(String source, ClassLoader loader, List<MethodStub> stubs) {
        Pattern anonPattern = Pattern.compile("new\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{");
        Matcher m = anonPattern.matcher(source);
        while (m.find()) {
            String typeName = m.group(1);
            int bracePos = m.end() - 1;

            Class<?> clazz = resolveClass(typeName, source, loader);
            if (clazz == null) continue;

            // 检查是否是接口或抽象类
            if (!clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers())) continue;

            int insertOffset = bracePos + 1;
            Set<String> existingMethods = collectExistingMethods(source, bracePos);
            String indent = detectIndent(source, bracePos) + "    ";

            List<Method> abstractMethods = new ArrayList<>();
            collectAbstractMethods(clazz, abstractMethods);

            StringBuilder sb = new StringBuilder();
            for (Method method : abstractMethods) {
                String methodSig = method.getName() + "(" + getParamTypeSignature(method) + ")";
                if (existingMethods.contains(methodSig) || existingMethods.contains(method.getName())) continue;
                sb.append(generateMethodCode(method, indent));
            }

            if (sb.length() > 0) {
                stubs.add(new MethodStub(insertOffset, sb.toString(), "anonymous " + typeName));
            }
        }
    }

    /**
     * 收集类/接口中所有需要实现的抽象方法。
     */
    private void collectAbstractMethods(Class<?> clazz, List<Method> result) {
        Set<String> seen = new HashSet<>();
        if (clazz.isInterface()) {
            for (Method m : clazz.getMethods()) {
                // 跳过 default 方法和 static 方法（Java 8+）
                if (m.isDefault() || Modifier.isStatic(m.getModifiers())) continue;
                // 跳过 Object 的方法
                if (isObjectMethod(m)) continue;
                String sig = m.getName() + "(" + getParamTypeSignature(m) + ")";
                if (!seen.contains(sig)) {
                    seen.add(sig);
                    result.add(m);
                }
            }
        } else {
            // 抽象类：收集所有 abstract 方法
            Class<?> c = clazz;
            while (c != null && c != Object.class) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!Modifier.isAbstract(m.getModifiers())) continue;
                    String sig = m.getName() + "(" + getParamTypeSignature(m) + ")";
                    if (!seen.contains(sig)) {
                        seen.add(sig);
                        result.add(m);
                    }
                }
                c = c.getSuperclass();
            }
            // 也收集实现的接口中的方法
            for (Class<?> iface : getAllInterfaces(clazz)) {
                for (Method m : iface.getMethods()) {
                    if (m.isDefault() || Modifier.isStatic(m.getModifiers())) continue;
                    if (isObjectMethod(m)) continue;
                    String sig = m.getName() + "(" + getParamTypeSignature(m) + ")";
                    if (!seen.contains(sig)) {
                        seen.add(sig);
                        result.add(m);
                    }
                }
            }
        }
    }

    private Set<Class<?>> getAllInterfaces(Class<?> clazz) {
        Set<Class<?>> result = new LinkedHashSet<>();
        Class<?> c = clazz;
        while (c != null) {
            for (Class<?> iface : c.getInterfaces()) {
                result.add(iface);
                addSuperInterfaces(iface, result);
            }
            c = c.getSuperclass();
        }
        return result;
    }

    private void addSuperInterfaces(Class<?> iface, Set<Class<?>> result) {
        for (Class<?> superIface : iface.getInterfaces()) {
            if (result.add(superIface)) {
                addSuperInterfaces(superIface, result);
            }
        }
    }

    private boolean isObjectMethod(Method m) {
        try {
            Object.class.getMethod(m.getName(), m.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * 生成单个方法的实现代码。
     */
    private String generateMethodCode(Method method, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(indent).append("@Override\n");
        sb.append(indent);

        // 访问修饰符
        sb.append("public ");

        // 返回类型
        Class<?> returnType = method.getReturnType();
        sb.append(returnType.getSimpleName()).append(" ");

        // 方法名
        sb.append(method.getName()).append("(");

        // 参数列表
        Class<?>[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes[i].getSimpleName()).append(" arg").append(i);
        }
        sb.append(")");

        // 异常声明
        Class<?>[] exceptions = method.getExceptionTypes();
        if (exceptions.length > 0) {
            sb.append(" throws ");
            for (int i = 0; i < exceptions.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(exceptions[i].getSimpleName());
            }
        }

        sb.append(" {\n");

        // 方法体：返回默认值
        sb.append(indent).append("    ");
        if (returnType == void.class) {
            sb.append("// TODO: 实现此方法\n");
        } else if (returnType == boolean.class) {
            sb.append("return false; // TODO\n");
        } else if (returnType.isPrimitive()) {
            sb.append("return 0; // TODO\n");
        } else {
            sb.append("return null; // TODO\n");
        }

        sb.append(indent).append("}\n");
        return sb.toString();
    }

    /**
     * 获取方法参数类型签名，用于方法去重。
     */
    private String getParamTypeSignature(Method m) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < m.getParameterTypes().length; i++) {
            if (i > 0) sb.append(",");
            sb.append(m.getParameterTypes()[i].getSimpleName());
        }
        return sb.toString();
    }

    /**
     * 收集类体中已有的方法名（简单匹配）。
     */
    private Set<String> collectExistingMethods(String source, int bracePos) {
        Set<String> methods = new HashSet<>();
        // 找到匹配的闭合大括号
        int depth = 1;
        int pos = bracePos + 1;
        while (pos < source.length() && depth > 0) {
            char c = source.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            pos++;
        }
        String classBody = source.substring(bracePos + 1, Math.min(pos - 1, source.length()));

        // 简单匹配方法声明
        Pattern methodPattern = Pattern.compile(
                "(?:public|protected|private)?\\s*(?:static\\s+)?(?:\\w+(?:<[^>]*>)?\\s+)(\\w+)\\s*\\(([^)]*)\\)");
        Matcher m = methodPattern.matcher(classBody);
        while (m.find()) {
            methods.add(m.group(1)); // 方法名
            // 也加上带参数签名的版本
            String params = m.group(2).trim();
            if (!params.isEmpty()) {
                StringBuilder sig = new StringBuilder(m.group(1) + "(");
                String[] parts = params.split(",");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) sig.append(",");
                    String[] tokens = parts[i].trim().split("\\s+");
                    if (tokens.length >= 1) sig.append(tokens[0]);
                }
                sig.append(")");
                methods.add(sig.toString());
            }
        }
        return methods;
    }

    /**
     * 在类体中找到合适的插入位置（最后一个方法之后，或 { 之后）。
     */
    private int findInsertPositionInClassBody(String source, int openBracePos) {
        // 找到匹配的闭合大括号
        int depth = 1;
        int pos = openBracePos + 1;
        int lastMethodEnd = openBracePos + 1;
        while (pos < source.length() && depth > 0) {
            char c = source.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    // 在闭合大括号之前插入
                    return pos;
                }
                if (depth == 1) lastMethodEnd = pos + 1;
            }
            pos++;
        }
        return lastMethodEnd;
    }

    /**
     * 检测当前位置的缩进。
     */
    private String detectIndent(String source, int pos) {
        // 找到当前行的开头
        int lineStart = source.lastIndexOf('\n', pos);
        if (lineStart < 0) lineStart = 0;
        else lineStart++;
        StringBuilder indent = new StringBuilder();
        for (int i = lineStart; i < source.length() && (source.charAt(i) == ' ' || source.charAt(i) == '\t'); i++) {
            indent.append(source.charAt(i));
        }
        return indent.toString() + "    "; // 类体内再缩进一级
    }

    /**
     * 使用项目 classpath 构建 ClassLoader。
     */
    private ClassLoader buildClassLoader(String[] projectClasspath) {
        if (projectClasspath == null || projectClasspath.length == 0) {
            return getClass().getClassLoader();
        }
        try {
            URL[] urls = new URL[projectClasspath.length];
            for (int i = 0; i < projectClasspath.length; i++) {
                urls[i] = new File(projectClasspath[i]).toURI().toURL();
            }
            return new URLClassLoader(urls, getClass().getClassLoader());
        } catch (Exception e) {
            return getClass().getClassLoader();
        }
    }

    /**
     * 解析类名为 Class 对象。
     * 如果无法从 classpath 加载，尝试从项目源码中解析接口/抽象类的方法。
     */
    private Class<?> resolveClass(String simpleName, String source, ClassLoader loader) {
        // 1. java.lang
        try { return loader.loadClass("java.lang." + simpleName); }
        catch (ClassNotFoundException ignored) {}

        // 2. import 语句
        Pattern importPattern = Pattern.compile("import\\s+([\\w.]+\\." + Pattern.quote(simpleName) + ")\\s*;");
        Matcher m = importPattern.matcher(source);
        if (m.find()) {
            try { return loader.loadClass(m.group(1)); }
            catch (ClassNotFoundException ignored) {}
        }

        // 3. JDK 类索引
        List<String> candidates = getJdkClassIndex().get(simpleName);
        if (candidates != null) {
            for (String fullName : candidates) {
                try { return loader.loadClass(fullName); }
                catch (ClassNotFoundException ignored) {}
            }
        }

        // 4. 直接尝试
        try { return loader.loadClass(simpleName); }
        catch (ClassNotFoundException ignored) {}

        return null;
    }

    /**
     * 从源码中解析接口/抽象类的方法签名（当类无法从 classpath 加载时的回退方案）。
     * 扫描项目工作区中的所有 .java 文件，查找匹配的接口或抽象类定义。
     */
    private List<SourceMethodInfo> resolveMethodsFromProjectSource(String typeName, String currentSource) {
        List<SourceMethodInfo> methods = new ArrayList<>();

        // 先在当前源码中查找（同文件定义的接口）
        methods.addAll(extractMethodsFromSource(typeName, currentSource));
        if (!methods.isEmpty()) return methods;

        // 扫描项目工作区中的其他源文件
        String workspacePath = Constants.workspacePath;
        if (workspacePath == null) return methods;

        File wsDir = new File(workspacePath);
        if (!wsDir.exists()) return methods;

        // 遍历工作区所有 .java 文件
        try {
            Files.walkFileTree(wsDir.toPath(), new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        try {
                            String content = new String(Files.readAllBytes(file), "UTF-8");
                            List<SourceMethodInfo> found = extractMethodsFromSource(typeName, content);
                            if (!found.isEmpty()) {
                                methods.addAll(found);
                                return java.nio.file.FileVisitResult.TERMINATE;
                            }
                        } catch (Exception ignored) {}
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {}

        return methods;
    }

    /**
     * 从单个源码文本中提取指定接口/抽象类的方法签名。
     */
    private List<SourceMethodInfo> extractMethodsFromSource(String typeName, String source) {
        List<SourceMethodInfo> methods = new ArrayList<>();

        // 匹配 interface TypeName { ... } 或 abstract class TypeName { ... }
        Pattern typePattern = Pattern.compile(
            "(?:interface|abstract\\s+class)\\s+" + Pattern.quote(typeName)
            + "(?:\\s+extends\\s+[\\w,\\s<>]+)?\\s*\\{");
        Matcher tm = typePattern.matcher(source);
        if (!tm.find()) return methods;

        // 找到类体
        int braceStart = tm.end() - 1;
        int depth = 1;
        int pos = braceStart + 1;
        while (pos < source.length() && depth > 0) {
            if (source.charAt(pos) == '{') depth++;
            else if (source.charAt(pos) == '}') depth--;
            pos++;
        }
        String body = source.substring(braceStart + 1, Math.max(braceStart + 1, pos - 1));

        // 提取方法签名（接口中的方法声明，或 abstract 方法）
        // 匹配: returnType methodName(params); 或 abstract returnType methodName(params);
        Pattern methodPattern = Pattern.compile(
            "(?:abstract\\s+)?([\\w<>\\[\\]]+)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[\\w,\\s]+)?\\s*;");
        Matcher mm = methodPattern.matcher(body);
        while (mm.find()) {
            String returnType = mm.group(1);
            String methodName = mm.group(2);
            String params = mm.group(3).trim();
            // 跳过 default 方法
            String before = body.substring(Math.max(0, mm.start() - 20), mm.start());
            if (before.contains("default ")) continue;
            methods.add(new SourceMethodInfo(returnType, methodName, params));
        }

        return methods;
    }

    /**
     * 从源码解析的方法信息
     */
    static class SourceMethodInfo {
        final String returnType;
        final String methodName;
        final String params; // 原始参数字符串，如 "String name, int age"

        SourceMethodInfo(String returnType, String methodName, String params) {
            this.returnType = returnType;
            this.methodName = methodName;
            this.params = params;
        }

        String generateCode(String indent) {
            StringBuilder sb = new StringBuilder();
            sb.append("\n").append(indent).append("@Override\n");
            sb.append(indent).append("public ").append(returnType).append(" ").append(methodName).append("(");
            sb.append(params);
            sb.append(") {\n");
            sb.append(indent).append("    ");
            if ("void".equals(returnType)) {
                sb.append("// TODO: 实现此方法\n");
            } else if ("boolean".equals(returnType)) {
                sb.append("return false; // TODO\n");
            } else if ("int".equals(returnType) || "long".equals(returnType) || "double".equals(returnType)
                    || "float".equals(returnType) || "short".equals(returnType) || "byte".equals(returnType)
                    || "char".equals(returnType)) {
                sb.append("return 0; // TODO\n");
            } else {
                sb.append("return null; // TODO\n");
            }
            sb.append(indent).append("}\n");
            return sb.toString();
        }
    }

    /**
     * 检测 Lambda 上下文：变量声明为函数式接口时，生成 Lambda 参数提示。
     * 例如: Runnable r = 时提示 () -> { }
     *       Comparator<String> c = 时提示 (String arg0, String arg1) -> { }
     */
    public String generateLambdaSnippet(String typeName, String source, String[] projectClasspath) {
        ClassLoader loader = buildClassLoader(projectClasspath);
        Class<?> clazz = resolveClass(typeName, source, loader);
        if (clazz == null || !clazz.isInterface()) return null;

        // 找到函数式接口的唯一抽象方法
        Method sam = findSingleAbstractMethod(clazz);
        if (sam == null) return null;

        StringBuilder sb = new StringBuilder("(");
        Class<?>[] paramTypes = sam.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes[i].getSimpleName()).append(" arg").append(i);
        }
        sb.append(") -> {\n    // TODO\n}");
        return sb.toString();
    }

    /**
     * 找到函数式接口的唯一抽象方法（SAM）。
     */
    private Method findSingleAbstractMethod(Class<?> iface) {
        Method sam = null;
        for (Method m : iface.getMethods()) {
            if (m.isDefault() || Modifier.isStatic(m.getModifiers())) continue;
            if (isObjectMethod(m)) continue;
            if (sam != null) return null; // 多于一个抽象方法，不是函数式接口
            sam = m;
        }
        return sam;
    }

    /**
     * 方法存根信息。
     */
    public static class MethodStub {
        private final int insertOffset;  // 在源码中的插入位置
        private final String code;       // 生成的方法代码
        private final String context;    // 上下文描述（类名等）

        public MethodStub(int insertOffset, String code, String context) {
            this.insertOffset = insertOffset;
            this.code = code;
            this.context = context;
        }

        public int getInsertOffset() { return insertOffset; }
        public String getCode() { return code; }
        public String getContext() { return context; }
    }

    /**
     * 补全建议项。
     */
    public static class CompletionProposal {
        public enum Kind { CLASS, METHOD, FIELD, VARIABLE, KEYWORD }

        private final String text;
        private final Kind kind;
        private final String label;
        private final String detail;
        private final ImportEdit importEdit;

        public CompletionProposal(String text, Kind kind, String label) {
            this(text, kind, label, null, null);
        }

        public CompletionProposal(String text, Kind kind, String label, String detail, ImportEdit importEdit) {
            this.text = text;
            this.kind = kind;
            this.label = label;
            this.detail = detail;
            this.importEdit = importEdit;
        }

        public String getText() { return text; }
        public Kind getKind() { return kind; }
        public String getLabel() { return label; }
        public String getDetail() { return detail; }
        public ImportEdit getImportEdit() { return importEdit; }

        /**
         * Import 编辑信息。
         */
        public static class ImportEdit {
            private final String text;       // "import java.util.Date;\n"
            private final int startLine;     // 替换起始行 (0-based), -1 表示新增
            private final int endLine;       // 替换结束行 (0-based, exclusive)
            private final boolean isReplace; // true=替换已有import, false=新增

            public ImportEdit(String text, int startLine, int endLine, boolean isReplace) {
                this.text = text;
                this.startLine = startLine;
                this.endLine = endLine;
                this.isReplace = isReplace;
            }

            public String getText() { return text; }
            public int getStartLine() { return startLine; }
            public int getEndLine() { return endLine; }
            public boolean isReplace() { return isReplace; }
        }
    }

    /**
     * 自定义 NameEnvironment，支持从内存中的打开文档解析类。
     * 这样可以在文件未保存时也能正确解析跨包引用。
     */
    private static class InMemoryNameEnvironment extends FileSystem {
        private final Map<String, String> openDocuments; // URI -> 内容
        private final Map<String, ICompilationUnit> inMemoryUnits; // 全限定类名 -> 编译单元

        public InMemoryNameEnvironment(String[] classpathDirectories, String[] sourcepathDirectories,
                                       Map<String, String> openDocuments) {
            super(classpathDirectories, sourcepathDirectories, "UTF-8");
            this.openDocuments = openDocuments;
            this.inMemoryUnits = new HashMap<>();
            
            // 预处理所有打开的文档，提取包名和类名
            for (Map.Entry<String, String> entry : openDocuments.entrySet()) {
                String uri = entry.getKey();
                String content = entry.getValue();
                
                // 从 URI 提取文件名
                String fileName = extractFileName(uri);
                if (!fileName.endsWith(".java")) continue;
                
                // 从内容提取包名
                String packageName = extractPackageFromSource(content);
                String className = fileName.replace(".java", "");
                String fullClassName = packageName != null && !packageName.isEmpty()
                    ? packageName + "." + className
                    : className;
                
                // 创建编译单元
                String unitName = fullClassName.replace('.', '/') + ".java";
                ICompilationUnit unit = new CompilationUnit(
                    content.toCharArray(),
                    unitName,
                    "UTF-8"
                );
                inMemoryUnits.put(fullClassName, unit);
            }
        }

        @Override
        public org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer findType(char[][] compoundTypeName) {
            // 先尝试从内存中的文档查找
            String fullName = charsToString(compoundTypeName);
            ICompilationUnit unit = inMemoryUnits.get(fullName);
            if (unit != null) {
                return new org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer(unit, null);
            }
            // 回退到文件系统
            return super.findType(compoundTypeName);
        }

        @Override
        public org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer findType(char[] typeName, char[][] packageName) {
            // 先尝试从内存中的文档查找
            String fullName = charsToString(packageName);
            if (!fullName.isEmpty()) fullName += ".";
            fullName += new String(typeName);
            
            ICompilationUnit unit = inMemoryUnits.get(fullName);
            if (unit != null) {
                return new org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer(unit, null);
            }
            // 回退到文件系统
            return super.findType(typeName, packageName);
        }

        private String charsToString(char[][] chars) {
            if (chars == null || chars.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < chars.length; i++) {
                if (i > 0) sb.append('.');
                sb.append(chars[i]);
            }
            return sb.toString();
        }

        private String extractFileName(String uri) {
            int lastSlash = uri.lastIndexOf('/');
            return lastSlash >= 0 ? uri.substring(lastSlash + 1) : uri;
        }
    }
}
