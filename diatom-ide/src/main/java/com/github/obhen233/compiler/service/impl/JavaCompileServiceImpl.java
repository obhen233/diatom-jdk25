package com.github.obhen233.compiler.service.impl;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.i18n.I18n;
import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.CompileResult;
import com.github.obhen233.compiler.exception.CompileException;
import com.github.obhen233.compiler.service.ClasspathBuilder;
import com.github.obhen233.compiler.service.JavaCompileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 使用 ECJ (Eclipse Compiler for Java) 按项目编译
 * - 扫描项目下所有 .java 源文件
 * - 自动收集 lib/*.jar、Maven/Gradle 本地仓库依赖到 classpath
 * - 根据配置的 JDK 版本输出对应字节码
 */
@Service
public class JavaCompileServiceImpl implements JavaCompileService {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ClasspathBuilder classpathBuilder;

    // ==================== 项目级编译（核心） ====================

    @Override
    public Class compile(String projectName, int jdkVersion, String mainClass) throws Exception {
        File projectDir = new File(Constants.workspacePath, projectName);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            throw new CompileException(I18n.get("compile.projectNotFound", projectName));
        }

        // 1. 确定源码根目录（先检测，避免重复扫描）
        List<String> sourceRoots = detectSourceRoots(projectDir);

        // 2. 收集所有 .java 源文件
        List<File> sourceFiles = collectJavaFiles(projectDir, sourceRoots);
        if (sourceFiles.isEmpty()) {
            throw new CompileException(I18n.get("compile.noJavaFiles"));
        }

        // 3. 构建 classpath（lib/*.jar + Maven/Gradle 依赖）
        String classpath = buildClasspath(projectDir);

        // 4. 输出目录
        File outputDir = new File(projectDir, "target" + File.separator + "classes");
        outputDir.mkdirs();

        // 5. 构建 ECJ 编译参数
        String versionStr = jdkVersion <= 8 ? "1." + jdkVersion : String.valueOf(jdkVersion);
        List<String> ecjArgs = new ArrayList<>();
        ecjArgs.add("-source");
        ecjArgs.add(versionStr);
        ecjArgs.add("-target");
        ecjArgs.add(versionStr);
        ecjArgs.add("-d");
        ecjArgs.add(outputDir.getAbsolutePath());
        ecjArgs.add("-encoding");
        ecjArgs.add("UTF-8");
        ecjArgs.add("-nowarn");
        // sourcepath
        if (!sourceRoots.isEmpty()) {
            ecjArgs.add("-sourcepath");
            ecjArgs.add(String.join(File.pathSeparator, sourceRoots));
        }
        // classpath
        if (!classpath.isEmpty()) {
            ecjArgs.add("-classpath");
            ecjArgs.add(classpath);
        }
        // 检测 Lombok 等注解处理器并启用
        String processorPath = classpathBuilder.detectAnnotationProcessors(classpath, projectDir);
        if (!processorPath.isEmpty()) {
            ecjArgs.add("-processorpath");
            ecjArgs.add(processorPath);
        }
        // 所有源文件
        for (File src : sourceFiles) {
            ecjArgs.add(src.getAbsolutePath());
        }

        // 6. 调用 ECJ 编译
        StringWriter errWriter = new StringWriter();
        StringWriter outWriter = new StringWriter();
        PrintWriter errPw = new PrintWriter(errWriter);
        PrintWriter outPw = new PrintWriter(outWriter);

        boolean success;
        try {
            org.eclipse.jdt.internal.compiler.batch.Main ecjMain =
                new org.eclipse.jdt.internal.compiler.batch.Main(outPw, errPw, false, null, null);
            success = ecjMain.compile(ecjArgs.toArray(new String[0]));
        } catch (Exception e) {
            throw new CompileException(I18n.get("compile.ecjError", e.getMessage()));
        }

        errPw.flush();
        outPw.flush();

        if (!success) {
            String errors = errWriter.toString();
            if (errors.isEmpty()) errors = outWriter.toString();
            logger.info("ECJ compile failed for project {}: {}", projectName, errors);
            throw new CompileException(I18n.get("compile.failed", String.valueOf(jdkVersion), errors));
        }

        logger.info("ECJ compile success for project {} (JDK {}, {} files)", projectName, jdkVersion, sourceFiles.size());

        // 7. 加载编译后的类
        ClassLoaderHolder holder = loadClassFromOutput(outputDir, classpath, mainClass);
        // 注意：返回的 Class 仍然持有对 ClassLoaderHolder.classLoader 的引用
        // 调用方通过 executeMainMethod* 执行完成后会关闭 ClassLoader
        return holder.clazz;
    }

    // ==================== 源文件收集 ====================

    /**
     * 持有加载的类及其 ClassLoader，实现 AutoCloseable 以确保 ClassLoader 可以被正确关闭。
     *
     * 使用方式:
     * 1. 通过 executeMainMethod* 方法自动管理（推荐）
     * 2. 或使用 try-with-resources 模式:
     *    <pre>
     *    try (ClassLoaderHolder holder = loadClassFromOutput(...)) {
     *        Class clazz = holder.clazz;
     *        // 使用 clazz
     *    } // 自动关闭 ClassLoader
     *    </pre>
     */
    private static class ClassLoaderHolder implements AutoCloseable {
        private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ClassLoaderHolder.class);

        final Class<?> clazz;
        final URLClassLoader classLoader;

        ClassLoaderHolder(Class<?> clazz, URLClassLoader classLoader) {
            this.clazz = clazz;
            this.classLoader = classLoader;
        }

        @Override
        public void close() {
            try {
                classLoader.close();
            } catch (Exception e) {
                logger.warn("Failed to close ClassLoader: {}", e.getMessage());
            }
        }
    }

    private List<File> collectJavaFiles(File projectDir) throws IOException {
        return collectJavaFiles(projectDir, detectSourceRoots(projectDir));
    }

    private List<File> collectJavaFiles(File projectDir, List<String> sourceRoots) throws IOException {
        List<File> javaFiles = new ArrayList<>();
        for (String root : sourceRoots) {
            File rootDir = new File(root);
            if (rootDir.exists() && rootDir.isDirectory()) {
                Files.walkFileTree(rootDir.toPath(), new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.toString().endsWith(".java")) {
                            javaFiles.add(file.toFile());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        return javaFiles;
    }

    /**
     * 检测项目的源码根目录
     * 自动识别单模块和多模块（Maven/Gradle）项目：
     * - Maven 多模块: 父 pom 有 <modules> 定义时，收集每个子模块的 src/main/java
     * - Gradle 多项目: settings.gradle 有 include 定义时，收集每个子项目的 src/main/java
     * - 单模块: src/main/java, src/test/java 或 src/
     */
    private List<String> detectSourceRoots(File projectDir) {
        // 检测是否为多模块项目
        List<File> childModules = classpathBuilder.getMavenChildModules(projectDir);
        if (childModules.isEmpty()) {
            childModules = classpathBuilder.getGradleChildProjects(projectDir);
        }

        if (!childModules.isEmpty()) {
            // 多模块：收集所有子模块的源码根目录
            List<String> roots = new ArrayList<>();
            for (File module : childModules) {
                addModuleSourceRoots(module, roots);
            }
            // 父项目本身也可能有源码
            addModuleSourceRoots(projectDir, roots);
            if (roots.isEmpty()) {
                roots.add(projectDir.getAbsolutePath());
            }
            logger.info("Detected multi-module project with {} modules, {} source roots",
                childModules.size(), roots.size());
            return roots;
        }

        // 单模块模式（原有逻辑）
        List<String> roots = new ArrayList<>();
        addModuleSourceRoots(projectDir, roots);
        if (roots.isEmpty()) {
            roots.add(projectDir.getAbsolutePath());
        }
        return roots;
    }

    /** 添加单个模块的标准源码根目录到列表 */
    private void addModuleSourceRoots(File dir, List<String> roots) {
        File mainJava = new File(dir, "src" + File.separator + "main" + File.separator + "java");
        File testJava = new File(dir, "src" + File.separator + "test" + File.separator + "java");
        if (mainJava.exists()) {
            roots.add(mainJava.getAbsolutePath());
            if (testJava.exists()) roots.add(testJava.getAbsolutePath());
        } else {
            File src = new File(dir, "src");
            if (src.exists()) {
                roots.add(src.getAbsolutePath());
            }
        }
    }

    // ==================== 多模块项目检测 ====================

    /**
     * 检测 Maven 多模块项目（父 pom 中包含 <modules> 定义）
     */
    private boolean isMavenMultiModule(File projectDir) {
        return !classpathBuilder.getMavenChildModules(projectDir).isEmpty();
    }

    /**
     * 检测 Gradle 多项目（settings.gradle 中包含 include 定义）
     */
    private boolean isGradleMultiProject(File projectDir) {
        return !classpathBuilder.getGradleChildProjects(projectDir).isEmpty();
    }

    // ==================== Classpath 构建 ====================

    /**
     * 构建编译 classpath:
     * 1. lib/*.jar（项目手动引入的 jar）
     * 2. Maven/Gradle 完整依赖（通过构建工具命令获取，或读取缓存的 .cp 文件）
     * 3. 多模块项目时，自动添加各子模块的编译输出目录
     */
    private String buildClasspath(File projectDir) {
        List<String> cpEntries = new ArrayList<>();

        // 1. lib/ 目录下的所有 jar
        addLibJars(projectDir, cpEntries);

        // 检测是否为多模块项目
        List<File> childModules = classpathBuilder.getMavenChildModules(projectDir);
        boolean isMultiModule = !childModules.isEmpty();
        if (childModules.isEmpty()) {
            childModules = classpathBuilder.getGradleChildProjects(projectDir);
            isMultiModule = !childModules.isEmpty();
        }

        if (isMultiModule) {
            // 多模块：从父项目解析 classpath（Maven/Gradle 工具本身会处理模块间依赖）
            File pomFile = new File(projectDir, "pom.xml");
            if (pomFile.exists()) {
                List<String> mvnCp = resolveFullMavenClasspath(projectDir);
                cpEntries.addAll(mvnCp);
            } else if (hasGradleBuildFile(projectDir)) {
                List<String> gradleCp = resolveFullGradleClasspath(projectDir);
                cpEntries.addAll(gradleCp);
            }

            // 添加各个子模块的编译输出目录到 classpath
            // 这确保未通过 Maven/Gradle 安装到本地仓库的模块间依赖也能被解析
            for (File module : childModules) {
                classpathBuilder.addModuleOutputDir(module, cpEntries);
            }
            // 父项目自身的输出目录
            classpathBuilder.addModuleOutputDir(projectDir, cpEntries);
        } else {
            // 2. Maven 依赖 (单模块)
            File pomFile = new File(projectDir, "pom.xml");
            if (pomFile.exists()) {
                List<String> mvnCp = resolveFullMavenClasspath(projectDir);
                cpEntries.addAll(mvnCp);
            }

            // 3. Gradle 依赖 (单模块)
            if (hasGradleBuildFile(projectDir)) {
                List<String> gradleCp = resolveFullGradleClasspath(projectDir);
                cpEntries.addAll(gradleCp);
            }
        }

        return cpEntries.stream().distinct().collect(Collectors.joining(File.pathSeparator));
    }

    /** 将 lib/ 目录下的 jar 加入 classpath（带安全校验） */
    private void addLibJars(File projectDir, List<String> cpEntries) {
        File libDir = new File(projectDir, "lib");
        if (libDir.exists() && libDir.isDirectory()) {
            // 安全校验：确保 libDir 在项目目录内
            Path libPath = libDir.toPath().normalize();
            Path projectPath = projectDir.toPath().normalize();
            if (!libPath.startsWith(projectPath)) {
                logger.warn("lib directory is outside project directory, skipping: {}", libDir);
                return;
            }

            File[] jars = libDir.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
            if (jars != null) {
                for (File jar : jars) {
                    // 验证 jar 路径在项目目录内，防止符号链接穿越
                    Path jarPath = jar.toPath().normalize();
                    if (jarPath.startsWith(projectPath)) {
                        cpEntries.add(jar.getAbsolutePath());
                    } else {
                        logger.warn("Skipping jar outside project directory: {}", jar);
                    }
                }
            }
        }
    }

    /** 检查目录下是否有 Gradle 构建文件 */
    private boolean hasGradleBuildFile(File projectDir) {
        return new File(projectDir, "build.gradle").exists() || new File(projectDir, "build.gradle.kts").exists();
    }

    /**
     * 获取 Maven 项目的完整依赖 classpath。
     * 优先读取 target/.cp 缓存文件，不存在则执行 mvn dependency:build-classpath 生成。
     */
    private List<String> resolveFullMavenClasspath(File projectDir) {
        // 先尝试读取已缓存的 .cp 文件
        File cpFile = new File(projectDir, "target" + File.separator + ".cp");
        if (cpFile.exists()) {
            List<String> cached = classpathBuilder.readCpFile(cpFile);
            if (!cached.isEmpty()) return cached;
        }

        // 执行 mvn dependency:build-classpath 生成
        try {
            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
            String mvnCmd = classpathBuilder.findMvnCommand(projectDir, isWin);

            List<String> command = new ArrayList<>();
            command.add(mvnCmd);
            command.add("dependency:build-classpath");
            command.add("-Dmdep.outputFile=" + cpFile.getAbsolutePath());
            command.add("-B");
            command.add("-q");

            String mavenSettings = classpathBuilder.getSettingValue("mavenUserSettings");
            if (mavenSettings != null && !mavenSettings.trim().isEmpty()) {
                command.add("-s");
                command.add(mavenSettings.trim());
            }
            String mavenRepo = classpathBuilder.getSettingValue("mavenLocalRepository");
            if (mavenRepo != null && !mavenRepo.trim().isEmpty()) {
                command.add("-Dmaven.repo.local=" + mavenRepo.trim());
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectDir);
            pb.redirectErrorStream(true);
            String javaHome = classpathBuilder.getSettingValue("javaHome");
            if (javaHome != null && !javaHome.trim().isEmpty()) {
                pb.environment().put("JAVA_HOME", javaHome.trim());
            }
            pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");

            Process process = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                while (br.readLine() != null) {}
            }
            int exitCode = process.waitFor();

            if (exitCode == 0 && cpFile.exists()) {
                List<String> result = classpathBuilder.readCpFile(cpFile);
                logger.info("Maven classpath resolved: {} jars for {}", result.size(), projectDir.getName());
                return result;
            } else {
                logger.warn("mvn dependency:build-classpath failed (exit={}) for {}", exitCode, projectDir.getName());
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve Maven classpath for {}: {}", projectDir.getName(), e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 获取 Gradle 项目的完整依赖 classpath。
     * 优先读取 build/.cp 缓存文件，不存在则执行 gradle printClasspath 生成。
     */
    private List<String> resolveFullGradleClasspath(File projectDir) {
        File cpFile = new File(projectDir, "build" + File.separator + ".cp");
        if (cpFile.exists()) {
            List<String> cached = classpathBuilder.readCpFile(cpFile);
            if (!cached.isEmpty()) return cached;
        }

        // 创建临时 init script
        File initScript = new File(projectDir, "build" + File.separator + ".cp-init.gradle");
        initScript.getParentFile().mkdirs();
        String scriptContent =
            "allprojects {\n" +
            "  task printClasspath {\n" +
            "    doLast {\n" +
            "      def cpFile = new File(projectDir, 'build/.cp')\n" +
            "      def cp = []\n" +
            "      try { configurations.compileClasspath.each { cp << it.absolutePath } } catch (Exception e) {}\n" +
            "      try { configurations.runtimeClasspath.each { if (!cp.contains(it.absolutePath)) cp << it.absolutePath } } catch (Exception e) {}\n" +
            "      cpFile.text = cp.join(File.pathSeparator)\n" +
            "    }\n" +
            "  }\n" +
            "}\n";
        try {
            Files.write(initScript.toPath(), scriptContent.getBytes("UTF-8"));

            try {
                boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
                String gradleCmd = classpathBuilder.findGradleCommand(projectDir, isWin);

                List<String> command = new ArrayList<>();
                command.add(gradleCmd);
                command.add("--init-script");
                command.add(initScript.getAbsolutePath());
                command.add("printClasspath");
                command.add("-q");

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(projectDir);
                pb.redirectErrorStream(true);
                String javaHome = classpathBuilder.getSettingValue("javaHome");
                if (javaHome != null && !javaHome.trim().isEmpty()) {
                    pb.environment().put("JAVA_HOME", javaHome.trim());
                }
                pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");

                Process process = pb.start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                    while (br.readLine() != null) {}
                }
                int exitCode = process.waitFor();

                if (exitCode == 0 && cpFile.exists()) {
                    List<String> result = classpathBuilder.readCpFile(cpFile);
                    logger.info("Gradle classpath resolved: {} jars for {}", result.size(), projectDir.getName());
                    return result;
                } else {
                    logger.warn("Gradle printClasspath failed (exit={}) for {}", exitCode, projectDir.getName());
                }
            } finally {
                // 确保临时 init script 被删除
                if (initScript.exists()) {
                    initScript.delete();
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve Gradle classpath for {}: {}", projectDir.getName(), e.getMessage());
        }
        return Collections.emptyList();
    }

    // ==================== 类加载 ====================

    /**
     * 从编译输出目录加载类，classpath 中的 jar 也加入 classloader
     * 返回 ClassLoaderHolder 以确保 ClassLoader 可以被正确关闭
     */
    private ClassLoaderHolder loadClassFromOutput(File outputDir, String classpath, String className) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(outputDir.toURI().toURL());
        if (classpath != null && !classpath.isEmpty()) {
            for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                File f = new File(entry);
                if (f.exists()) urls.add(f.toURI().toURL());
            }
        }
        URLClassLoader classLoader = new URLClassLoader(
            urls.toArray(new URL[0]), getClass().getClassLoader());
        try {
            Class<?> clazz = classLoader.loadClass(className);
            return new ClassLoaderHolder(clazz, classLoader);
        } catch (ClassNotFoundException e) {
            // 确保 classLoader 被关闭
            try { classLoader.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    // ==================== 执行 Main 方法 ====================

    @Override
    public ApiResponse<CompileResult> executeMainMethod(Class clazz) throws Exception {
        return executeMainMethodWithClass(clazz, new String[]{});
    }

    @Override
    public ApiResponse<CompileResult> executeMainMethod(Class clazz, String[] args) throws Exception {
        return executeMainMethodWithClass(clazz, args);
    }

    @Override
    public ApiResponse<CompileResult> executeMainMethod(Class clazz, Long timeLimit) throws Exception {
        return executeMainMethod(clazz, timeLimit, new String[]{});
    }

    @Override
    public ApiResponse<CompileResult> executeMainMethod(Class clazz, Long timeLimit, String[] args) throws Exception {
        // 使用 FutureTask + 虚拟线程管理用户 main 方法执行：
        // 超时后 cancel(true) 中断执行中的虚拟线程（interrupt-based，不使用已废弃的 Thread.stop()）。
        // 说明：JDK 25 中 StructuredTaskScope 仍是预览 API（API 已重构且需 --enable-preview），
        // 故沿用稳定的 FutureTask 方案，仅将平台线程升级为虚拟线程以适配 JDK 25。
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        FutureTask<ApiResponse<CompileResult>> futureTask = new FutureTask<>(() -> executeMainMethodWithClass(clazz, args));
        executorService.submit(futureTask);
        try {
            return futureTask.get(timeLimit, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            futureTask.cancel(true);
            throw new RuntimeException(I18n.get("compile.timeout", timeLimit));
        } finally {
            executorService.shutdownNow();
        }
    }

    private ApiResponse<CompileResult> executeMainMethodWithClass(Class clazz, String[] args) throws Exception {
        setInputArgs(args);
        ByteArrayOutputStream baoStream = new ByteArrayOutputStream(1024);
        PrintStream cacheStream = new PrintStream(baoStream);
        PrintStream oldStream = System.out;
        PrintStream oldErr = System.err;
        System.setOut(cacheStream);
        System.setErr(cacheStream);

        // 记录执行前已有的线程，用于执行后清理用户代码创建的线程
        Set<Thread> threadsBefore = Thread.getAllStackTraces().keySet();

        Method method = clazz.getMethod(Constants.executeMainMethodName, String[].class);
        long startTime = System.currentTimeMillis();
        try {
            method.invoke(null, (Object) args);
        } finally {
            System.setOut(oldStream);
            System.setErr(oldErr);

            // 清理用户代码创建的线程（如 Redisson-Netty、Timer 等）
            cleanupUserThreads(threadsBefore, clazz.getClassLoader());

            // 关闭 URLClassLoader，释放 jar 文件句柄
            ClassLoader cl = clazz.getClassLoader();
            if (cl instanceof URLClassLoader) {
                try { ((URLClassLoader) cl).close(); } catch (Exception ignored) {}
            }
        }
        long endTime = System.currentTimeMillis();

        CompileResult cr = new CompileResult();
        cr.setResult(baoStream.toString("UTF-8"));
        cr.setDurationTime(endTime - startTime);
        cr.setType("ok");
        cr.setMessage("ok");
        return ApiResponse.ok(cr);
    }

    /**
     * 清理用户代码执行期间创建的线程。
     * 策略：执行前快照所有线程，执行后新增的线程全部视为用户线程并清理。
     * 仅排除已知的安全系统线程（GC、Finalizer 等）。
     */
    private void cleanupUserThreads(Set<Thread> threadsBefore, ClassLoader userClassLoader) {
        // 等一小段时间让短生命周期线程自行结束
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        Set<Thread> threadsAfter = Thread.getAllStackTraces().keySet();
        List<Thread> toClean = new ArrayList<>();
        for (Thread t : threadsAfter) {
            if (threadsBefore.contains(t)) continue;
            if (t == Thread.currentThread()) continue;
            if (!t.isAlive()) continue;
            // 排除 JVM 内部线程
            String name = t.getName();
            if (name.startsWith("Reference Handler") || name.startsWith("Finalizer")
                    || name.startsWith("Signal Dispatcher") || name.startsWith("GC ")
                    || name.startsWith("VM ") || name.startsWith("Attach Listener")) {
                continue;
            }
            toClean.add(t);
        }

        if (toClean.isEmpty()) return;
        logger.info("Cleaning up {} user threads after execution", toClean.size());

        // 第一轮：interrupt
        for (Thread t : toClean) {
            try { t.interrupt(); } catch (Exception e) { logger.warn("Failed to interrupt thread '{}': {}", t.getName(), e.getMessage()); }
        }

        // 等待线程响应 interrupt
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        // 第二轮：对仍然存活的线程，设为守护线程（防止阻止 JVM 关闭）并再次 interrupt
        for (Thread t : toClean) {
            if (!t.isAlive()) continue;
            try {
                t.setDaemon(true);
            } catch (Exception e) {
                // 已启动的线程无法 setDaemon，忽略
            }
            // 再次 interrupt，不使用已废弃的 Thread.stop()
            try {
                t.interrupt();
            } catch (Exception e) { logger.warn("Failed to interrupt thread '{}': {}", t.getName(), e.getMessage()); }
        }

        // 最终等待
        for (Thread t : toClean) {
            if (!t.isAlive()) continue;
            try { t.join(500); } catch (Exception e) { logger.warn("Failed to join thread '{}': {}", t.getName(), e.getMessage()); }
            if (t.isAlive()) {
                logger.warn("Thread '{}' could not be stopped, it may leak", t.getName());
            }
        }
    }

    private void setInputArgs(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (String arg : args) { sb.append(arg).append(" "); }
        System.setIn(new BufferedInputStream(new ByteArrayInputStream(sb.toString().getBytes())));
    }
}
