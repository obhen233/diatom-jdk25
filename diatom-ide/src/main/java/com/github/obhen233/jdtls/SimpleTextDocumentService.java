package com.github.obhen233.jdtls;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.entity.IdeSetting;
import com.github.obhen233.compiler.repository.IdeSettingRepository;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接入 JDT Core 的 TextDocumentService 实现。
 * 提供基于 AST 的代码补全和实时诊断功能。
 * 支持项目级 classpath（JDK + lib/*.jar + 已编译 class）。
 */
@Service
public class SimpleTextDocumentService implements TextDocumentService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SimpleTextDocumentService.class);

    private final JdtCoreService jdtCoreService = new JdtCoreService();

    /** 缓存已打开文档的内容，key = URI */
    private final Map<String, String> openDocuments = new ConcurrentHashMap<>();

    /** 文档最后访问时间，用于清理过期文档 */
    private final Map<String, Long> documentAccessTime = new ConcurrentHashMap<>();

    /** 文档缓存过期时间（毫秒）：30分钟 */
    private static final long DOCUMENT_EXPIRE_MS = 30 * 60 * 1000;

    /** 清理过期文档的调度器 */
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "open-documents-cleanup");
        t.setDaemon(true);
        return t;
    });

    {
        // 每5分钟清理一次过期文档
        cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredDocuments, 5, 5, TimeUnit.MINUTES);
    }

    private void cleanupExpiredDocuments() {
        long now = System.currentTimeMillis();
        long expiryThreshold = now - DOCUMENT_EXPIRE_MS;
        documentAccessTime.entrySet().removeIf(entry -> {
            if (entry.getValue() < expiryThreshold) {
                openDocuments.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    private void touchDocument(String uri) {
        documentAccessTime.put(uri, System.currentTimeMillis());
    }

    /**
     * 关闭清理调度器，释放资源
     */
    @Override
    public void close() {
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 用于推送诊断信息的客户端引用 */
    private LanguageClient client;

    /** IDE 设置仓库（由 Spring 注入） */
    private final IdeSettingRepository settingRepo;

    @Autowired
    public SimpleTextDocumentService(IdeSettingRepository settingRepo) {
        this.settingRepo = settingRepo;
    }

    private IdeSettingRepository getSettingRepo() {
        return this.settingRepo;
    }

    public void setClient(LanguageClient client) {
        this.client = client;
    }

    /**
     * 从 URI 中提取项目名称。
     * URI 格式: file:///workspace/{projectName}/path/to/File.java
     */
    private String extractProjectName(String uri) {
        // file:///workspace/project1/src/Main.java -> project1
        String prefix = "file:///workspace/";
        if (!uri.startsWith(prefix)) return null;
        String rest = uri.substring(prefix.length());
        int slash = rest.indexOf('/');
        return slash > 0 ? rest.substring(0, slash) : rest;
    }

    /**
     * 根据项目名构建项目级 classpath。
     * 包含: lib/*.jar + Maven/Gradle 依赖 + src 目录 + 编译输出
     */
    private String[] resolveProjectClasspath(String projectName) {
        if (projectName == null || projectName.isEmpty()) return null;
        String workspacePath = Constants.workspacePath;
        if (workspacePath == null) return null;

        java.io.File projectDir = new java.io.File(workspacePath, projectName);
        if (!projectDir.exists()) return null;

        List<String> paths = new ArrayList<>();

        // 1. lib/*.jar — 用户手工导入的 jar
        java.io.File libDir = new java.io.File(projectDir, "lib");
        if (libDir.exists() && libDir.isDirectory()) {
            java.io.File[] jars = libDir.listFiles((d, name) -> name.endsWith(".jar"));
            if (jars != null) {
                for (java.io.File jar : jars) {
                    paths.add(jar.getAbsolutePath());
                }
            }
        }

        // 2. Maven 依赖 — 优先用 mvn dependency:build-classpath 获取完整 classpath
        java.io.File pomFile = new java.io.File(projectDir, "pom.xml");
        if (pomFile.exists()) {
            List<String> mvnCp = resolveMavenClasspath(projectDir);
            if (mvnCp != null && !mvnCp.isEmpty()) {
                paths.addAll(mvnCp);
            } else {
                // 回退：手动解析 pom.xml
                addMavenDependencyJars(pomFile, paths);
            }
        }

        // 3. Gradle 依赖 — 优先用 gradle 命令获取完整 classpath
        java.io.File gradleFile = new java.io.File(projectDir, "build.gradle");
        java.io.File gradleKtsFile = new java.io.File(projectDir, "build.gradle.kts");
        if (gradleFile.exists() || gradleKtsFile.exists()) {
            List<String> gradleCp = resolveGradleClasspath(projectDir);
            if (gradleCp != null && !gradleCp.isEmpty()) {
                paths.addAll(gradleCp);
            } else {
                // 回退：手动解析 build.gradle
                if (gradleFile.exists()) addGradleDependencyJars(gradleFile, paths);
            }
        }

        // 4. 项目源码目录
        java.io.File srcMainJava = new java.io.File(projectDir, "src/main/java");
        if (srcMainJava.exists()) {
            paths.add(srcMainJava.getAbsolutePath());
        } else {
            java.io.File srcDir = new java.io.File(projectDir, "src");
            if (srcDir.exists()) paths.add(srcDir.getAbsolutePath());
        }

        // 5. 编译输出目录
        java.io.File targetClasses = new java.io.File(projectDir, "target/classes");
        if (targetClasses.exists()) paths.add(targetClasses.getAbsolutePath());
        // Gradle 编译输出
        java.io.File buildClasses = new java.io.File(projectDir, "build/classes/java/main");
        if (buildClasses.exists()) paths.add(buildClasses.getAbsolutePath());
        java.io.File buildClasses2 = new java.io.File(projectDir, "build/classes/main");
        if (buildClasses2.exists() && !buildClasses.exists()) paths.add(buildClasses2.getAbsolutePath());

        if (paths.isEmpty()) return null;
        log.info("[LSP] Project classpath for {}: {} entries", projectName, paths.size());
        return paths.toArray(new String[0]);
    }

    /** Maven classpath 缓存: projectDir -> (timestamp, classpath) */
    private static final java.util.Map<String, long[]> mvnCpTimestamps = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, List<String>> mvnCpCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 清除指定项目的 Maven classpath 缓存 */
    public static void invalidateMavenClasspathCache(String projectName) {
        // 使用精确匹配：key 必须以 "/项目名" 或 "/项目名/" 结尾，避免误匹配 "project-core"
        String suffix1 = java.io.File.separator + projectName;
        String suffix2 = java.io.File.separator + projectName + java.io.File.separator;
        mvnCpCache.entrySet().removeIf(e -> {
            String key = e.getKey();
            return key.endsWith(suffix1) || key.endsWith(suffix2) || key.equals(projectName);
        });
        mvnCpTimestamps.entrySet().removeIf(e -> {
            String key = e.getKey();
            return key.endsWith(suffix1) || key.endsWith(suffix2) || key.equals(projectName);
        });
        log.info("[LSP] Maven classpath cache invalidated for: {}", projectName);
    }

    /**
     * 使用 mvn dependency:build-classpath 获取 Maven 项目的完整依赖 classpath。
     * 结果缓存 60 秒。
     */
    private List<String> resolveMavenClasspath(java.io.File projectDir) {
        String key = projectDir.getAbsolutePath();
        long now = System.currentTimeMillis();

        // 使用 computeIfAbsent 确保原子性检查+获取
        List<String> cached = mvnCpCache.get(key);
        long[] ts = mvnCpTimestamps.get(key);
        if (cached != null && ts != null && (now - ts[0]) < 60_000) {
            return cached;
        }

        try {
            // 查找 mvn 命令
            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
            String mvnCmd;
            java.io.File mvnw = new java.io.File(projectDir, isWin ? "mvnw.cmd" : "mvnw");
            if (mvnw.exists()) {
                mvnCmd = mvnw.getAbsolutePath();
            } else {
                // 尝试从 IDE settings 读取 MAVEN_HOME
                String mavenHome = getIdeSetting("mavenHome");
                if (mavenHome != null && !mavenHome.trim().isEmpty()) {
                    java.io.File mvnBin = new java.io.File(mavenHome, "bin" + java.io.File.separator + (isWin ? "mvn.cmd" : "mvn"));
                    mvnCmd = mvnBin.exists() ? mvnBin.getAbsolutePath() : (isWin ? "mvn.cmd" : "mvn");
                } else {
                    mvnCmd = isWin ? "mvn.cmd" : "mvn";
                }
            }

            List<String> command = new ArrayList<>();
            command.add(mvnCmd);
            command.add("dependency:build-classpath");
            command.add("-Dmdep.outputFile=" + projectDir.getAbsolutePath() + java.io.File.separator + "target" + java.io.File.separator + ".cp");
            command.add("-B");
            command.add("-q");

            // 如果配置了 Maven settings
            String mavenSettings = getIdeSetting("mavenUserSettings");
            if (mavenSettings != null && !mavenSettings.trim().isEmpty()) {
                command.add("-s");
                command.add(mavenSettings.trim());
            }
            String mavenRepo = getIdeSetting("mavenLocalRepository");
            if (mavenRepo != null && !mavenRepo.trim().isEmpty()) {
                command.add("-Dmaven.repo.local=" + mavenRepo.trim());
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectDir);
            pb.redirectErrorStream(true);
            String javaHome = getIdeSetting("javaHome");
            if (javaHome != null && !javaHome.trim().isEmpty()) {
                pb.environment().put("JAVA_HOME", javaHome.trim());
            }
            pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");

            Process process = pb.start();
            // 消费输出防止阻塞
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), "UTF-8"))) {
                while (br.readLine() != null) {}
            }
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                java.io.File cpFile = new java.io.File(projectDir, "target/.cp");
                if (cpFile.exists()) {
                    String cpContent = new String(java.nio.file.Files.readAllBytes(cpFile.toPath()), "UTF-8").trim();
                    if (!cpContent.isEmpty()) {
                        String[] entries = cpContent.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator));
                        List<String> result = new ArrayList<>();
                        for (String entry : entries) {
                            if (!entry.trim().isEmpty() && new java.io.File(entry.trim()).exists()) {
                                result.add(entry.trim());
                            }
                        }
                        mvnCpCache.put(key, result);
                        mvnCpTimestamps.put(key, new long[]{now});
                        log.info("[LSP] Maven classpath resolved: {} jars for {}", result.size(), projectDir.getName());
                        return result;
                    }
                }
            } else {
                log.error("[LSP] mvn dependency:build-classpath failed (exit={}) for {}", exitCode, projectDir.getName());
            }
        } catch (Exception e) {
            log.error("[LSP] Failed to resolve Maven classpath: {}", e.getMessage());
        }
        return null;
    }

    /** 
     * 读取 IDE 设置。
     * 优先级: SQLite > 系统环境变量
     * (SimpleTextDocumentService 不是 Spring Bean，无法注入 Environment，
     *  所以直接回退到环境变量)
     */
    private String getIdeSetting(String key) {
        // 1. 从 SQLite 读取
        IdeSettingRepository repo = getSettingRepo();
        if (repo != null) {
            try {
                String val = repo.findById(key)
                        .map(IdeSetting::getValue)
                        .orElse(null);
                if (val != null && !val.trim().isEmpty()) return val.trim();
            } catch (Exception e) { /* ignore */ }
        }
        // 2. 从系统环境变量回退
        switch (key) {
            case "javaHome":
                String jh = System.getProperty("java.home");
                if (jh != null && !jh.isEmpty()) return jh;
                return System.getenv("JAVA_HOME");
            case "mavenHome":
                String m2 = System.getenv("M2_HOME");
                if (m2 != null && !m2.isEmpty()) return m2;
                return System.getenv("MAVEN_HOME");
            case "gradleUserHome":
                return System.getenv("GRADLE_USER_HOME");
            default:
                return null;
        }
    }

    /** Gradle classpath 缓存 */
    private static final java.util.Map<String, long[]> gradleCpTimestamps = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, List<String>> gradleCpCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 清除指定项目的 Gradle classpath 缓存 */
    public static void invalidateGradleClasspathCache(String projectName) {
        // 使用精确匹配：key 必须以 "/项目名" 或 "/项目名/" 结尾，避免误匹配 "project-core"
        String suffix1 = java.io.File.separator + projectName;
        String suffix2 = java.io.File.separator + projectName + java.io.File.separator;
        gradleCpCache.entrySet().removeIf(e -> {
            String key = e.getKey();
            return key.endsWith(suffix1) || key.endsWith(suffix2) || key.equals(projectName);
        });
        gradleCpTimestamps.entrySet().removeIf(e -> {
            String key = e.getKey();
            return key.endsWith(suffix1) || key.endsWith(suffix2) || key.equals(projectName);
        });
        log.info("[LSP] Gradle classpath cache invalidated for: {}", projectName);
    }

    /**
     * 使用 Gradle 命令获取项目的完整编译 classpath。
     * 通过注入一个临时 init script 来打印 runtimeClasspath 配置。
     * 结果缓存 60 秒。
     */
    private List<String> resolveGradleClasspath(java.io.File projectDir) {
        String key = projectDir.getAbsolutePath();
        long now = System.currentTimeMillis();

        // 使用 get 确保原子性检查+获取
        List<String> cached = gradleCpCache.get(key);
        long[] ts = gradleCpTimestamps.get(key);
        if (cached != null && ts != null && (now - ts[0]) < 60_000) {
            return cached;
        }

        try {
            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");

            // 查找 gradle 命令
            String gradleCmd;
            java.io.File gradlew = new java.io.File(projectDir, isWin ? "gradlew.bat" : "gradlew");
            if (gradlew.exists()) {
                gradleCmd = gradlew.getAbsolutePath();
            } else {
                String gradleHome = getIdeSetting("gradleUserHome");
                if (gradleHome != null && !gradleHome.trim().isEmpty()) {
                    java.io.File gradleBin = new java.io.File(gradleHome, "bin" + java.io.File.separator + (isWin ? "gradle.bat" : "gradle"));
                    gradleCmd = gradleBin.exists() ? gradleBin.getAbsolutePath() : (isWin ? "gradle.bat" : "gradle");
                } else {
                    gradleCmd = isWin ? "gradle.bat" : "gradle";
                }
            }

            // 创建临时 init script 来输出 classpath
            java.io.File initScript = new java.io.File(projectDir, "build" + java.io.File.separator + ".cp-init.gradle");
            initScript.getParentFile().mkdirs();
            String scriptContent =
                "allprojects {\n" +
                "  task printClasspath {\n" +
                "    doLast {\n" +
                "      def cpFile = new File(projectDir, 'build/.cp')\n" +
                "      def cp = []\n" +
                "      try {\n" +
                "        configurations.compileClasspath.each { cp << it.absolutePath }\n" +
                "      } catch (Exception e) {\n" +
                "        try {\n" +
                "          configurations.compile.each { cp << it.absolutePath }\n" +
                "        } catch (Exception e2) {}\n" +
                "      }\n" +
                "      try {\n" +
                "        configurations.runtimeClasspath.each { if (!cp.contains(it.absolutePath)) cp << it.absolutePath }\n" +
                "      } catch (Exception e) {}\n" +
                "      cpFile.text = cp.join(File.pathSeparator)\n" +
                "    }\n" +
                "  }\n" +
                "}\n";
            java.nio.file.Files.write(initScript.toPath(), scriptContent.getBytes("UTF-8"));

            List<String> command = new ArrayList<>();
            command.add(gradleCmd);
            command.add("--init-script");
            command.add(initScript.getAbsolutePath());
            command.add("printClasspath");
            command.add("-q");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectDir);
            pb.redirectErrorStream(true);
            String javaHome = getIdeSetting("javaHome");
            if (javaHome != null && !javaHome.trim().isEmpty()) {
                pb.environment().put("JAVA_HOME", javaHome.trim());
            }
            pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");

            Process process = pb.start();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), "UTF-8"))) {
                while (br.readLine() != null) {}
            }
            int exitCode = process.waitFor();

            // 清理临时脚本
            initScript.delete();

            if (exitCode == 0) {
                java.io.File cpFile = new java.io.File(projectDir, "build/.cp");
                if (cpFile.exists()) {
                    String cpContent = new String(java.nio.file.Files.readAllBytes(cpFile.toPath()), "UTF-8").trim();
                    if (!cpContent.isEmpty()) {
                        String[] entries = cpContent.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator));
                        List<String> result = new ArrayList<>();
                        for (String entry : entries) {
                            if (!entry.trim().isEmpty() && new java.io.File(entry.trim()).exists()) {
                                result.add(entry.trim());
                            }
                        }
                        gradleCpCache.put(key, result);
                        gradleCpTimestamps.put(key, new long[]{now});
                        log.info("[LSP] Gradle classpath resolved: {} jars for {}", result.size(), projectDir.getName());
                        return result;
                    }
                }
            } else {
                log.error("[LSP] Gradle printClasspath failed (exit={}) for {}", exitCode, projectDir.getName());
            }
        } catch (Exception e) {
            log.error("[LSP] Failed to resolve Gradle classpath: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从 pom.xml 解析依赖，尝试在 Maven 本地仓库中找到对应的 jar。
     */
    private void addMavenDependencyJars(java.io.File pomFile, List<String> paths) {
        String m2Repo = System.getProperty("user.home") + java.io.File.separator
                + ".m2" + java.io.File.separator + "repository";
        try {
            String content = new String(java.nio.file.Files.readAllBytes(pomFile.toPath()), "UTF-8");
            java.util.regex.Pattern depPattern = java.util.regex.Pattern.compile(
                    "<dependency>\\s*" +
                    "<groupId>([^<]+)</groupId>\\s*" +
                    "<artifactId>([^<]+)</artifactId>\\s*" +
                    "(?:<version>([^<]+)</version>)?",
                    java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = depPattern.matcher(content);
            while (m.find()) {
                String groupId = m.group(1).trim();
                String artifactId = m.group(2).trim();
                String version = m.group(3) != null ? m.group(3).trim() : "";
                if (version.isEmpty()) continue;
                String jarPath = m2Repo + java.io.File.separator
                        + groupId.replace('.', java.io.File.separatorChar) + java.io.File.separator
                        + artifactId + java.io.File.separator
                        + version + java.io.File.separator
                        + artifactId + "-" + version + ".jar";
                java.io.File jarFile = new java.io.File(jarPath);
                if (jarFile.exists()) {
                    paths.add(jarFile.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            log.error("[LSP] Failed to parse pom.xml: {}", e.getMessage());
        }
    }

    /**
     * 从 build.gradle 解析依赖，尝试在 Gradle 缓存中找到对应的 jar。
     */
    private void addGradleDependencyJars(java.io.File gradleFile, List<String> paths) {
        String gradleCache = System.getProperty("user.home") + java.io.File.separator
                + ".gradle" + java.io.File.separator + "caches" + java.io.File.separator
                + "modules-2" + java.io.File.separator + "files-2.1";
        try {
            String content = new String(java.nio.file.Files.readAllBytes(gradleFile.toPath()), "UTF-8");
            // 匹配 implementation 'group:artifact:version' 或 compile 'group:artifact:version'
            java.util.regex.Pattern depPattern = java.util.regex.Pattern.compile(
                    "(?:implementation|compile|api|runtimeOnly|compileOnly)\\s+['\"]([^:]+):([^:]+):([^'\"]+)['\"]");
            java.util.regex.Matcher m = depPattern.matcher(content);
            while (m.find()) {
                String groupId = m.group(1).trim();
                String artifactId = m.group(2).trim();
                String version = m.group(3).trim();
                // Gradle 缓存结构: ~/.gradle/caches/modules-2/files-2.1/{group}/{artifact}/{version}/{hash}/{artifact}-{version}.jar
                java.io.File depDir = new java.io.File(gradleCache,
                        groupId + java.io.File.separator + artifactId + java.io.File.separator + version);
                if (depDir.exists()) {
                    findJarsRecursive(depDir, artifactId + "-" + version + ".jar", paths);
                }
            }
        } catch (Exception e) {
            log.error("[LSP] Failed to parse build.gradle: {}", e.getMessage());
        }
    }

    private void findJarsRecursive(java.io.File dir, String jarName, List<String> paths) {
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.isDirectory()) findJarsRecursive(f, jarName, paths);
            else if (f.getName().equals(jarName)) paths.add(f.getAbsolutePath());
        }
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            String source = openDocuments.getOrDefault(uri, "");
            touchDocument(uri); // 刷新访问时间
            log.info("[LSP] completion request: uri={}, source.length={}, position={}:{}", uri, source.length(), params.getPosition().getLine(), params.getPosition().getCharacter());

            if (source.isEmpty()) {
                log.info("[LSP] completion: source is empty, returning empty list");
                return Either.forLeft(Collections.<CompletionItem>emptyList());
            }

            // 计算光标在源码中的偏移量
            int offset = computeOffset(source, params.getPosition());
            String unitName = extractUnitName(uri);
            String projectName = extractProjectName(uri);
            String[] projectClasspath = resolveProjectClasspath(projectName);

            List<JdtCoreService.CompletionProposal> proposals =
                    jdtCoreService.complete(source, unitName, offset, projectClasspath);

            log.info("[LSP] completion: got {} proposals", proposals.size());

            List<CompletionItem> items = new ArrayList<>();
            for (JdtCoreService.CompletionProposal p : proposals) {
                CompletionItem item = new CompletionItem(p.getLabel());
                item.setInsertText(p.getText());
                item.setKind(mapKind(p.getKind()));
                if (p.getDetail() != null) {
                    item.setDetail(p.getDetail());
                }
                // 处理 import 编辑
                JdtCoreService.CompletionProposal.ImportEdit importEdit = p.getImportEdit();
                if (importEdit != null) {
                    TextEdit edit;
                    if (importEdit.isReplace()) {
                        // 替换已有的 import 行
                        edit = new TextEdit(
                                new Range(
                                        new Position(importEdit.getStartLine(), 0),
                                        new Position(importEdit.getEndLine(), 0)
                                ),
                                importEdit.getText()
                        );
                    } else {
                        // 新增 import
                        int importLine = findImportInsertLine(source);
                        edit = new TextEdit(
                                new Range(new Position(importLine, 0), new Position(importLine, 0)),
                                importEdit.getText()
                        );
                    }
                    item.setAdditionalTextEdits(Collections.singletonList(edit));
                    log.info("[LSP] completion item '{}' has import edit: {} ({}), startLine={}",
                            p.getLabel(), importEdit.getText().trim(),
                            importEdit.isReplace() ? "replace line" : "new",
                            importEdit.getStartLine());
                }
                items.add(item);
            }
            return Either.<List<CompletionItem>, CompletionList>forLeft(items);
        });
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String text = params.getTextDocument().getText();
        openDocuments.put(uri, text);
        touchDocument(uri);
        // 刷新项目类索引，确保新文件被识别
        String projectName = extractProjectName(uri);
        if (projectName != null) {
            jdtCoreService.refreshProjectIndex(projectName);
        }
        publishDiagnostics(uri, text);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        // 使用 Full sync，取最后一个变更即为完整内容
        List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
        if (!changes.isEmpty()) {
            String text = changes.get(changes.size() - 1).getText();
            openDocuments.put(uri, text);
            touchDocument(uri);
            publishDiagnostics(uri, text);
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        openDocuments.remove(uri);
        documentAccessTime.remove(uri);
        // 清除该文件的诊断
        if (client != null) {
            client.publishDiagnostics(new PublishDiagnosticsParams(
                    uri, Collections.<Diagnostic>emptyList()));
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String text = openDocuments.get(uri);
        if (text != null) {
            // 刷新项目类索引，确保新保存的类被识别
            String projectName = extractProjectName(uri);
            if (projectName != null) {
                jdtCoreService.refreshProjectIndex(projectName);
            }
            publishDiagnostics(uri, text);
        }
    }

    /**
     * 提供代码操作（快速修复）。
     * 当检测到 "must implement abstract method" 类错误时，提供自动生成方法存根的修复。
     */
    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            List<Either<Command, CodeAction>> actions = new ArrayList<>();
            String uri = params.getTextDocument().getUri();
            String source = openDocuments.getOrDefault(uri, "");
            touchDocument(uri); // 刷新访问时间
            if (source.isEmpty()) return actions;

            // 检查诊断中是否有 "must implement" 相关错误
            boolean hasImplementError = false;
            for (Diagnostic d : params.getContext().getDiagnostics()) {
                String msg = d.getMessage();
                if (msg != null && (msg.contains("must implement") || msg.contains("must be implemented")
                        || msg.contains("abstract method") || msg.contains("is not abstract")
                        || msg.contains("does not override") || msg.contains("implement the inherited")
                        || msg.contains("must override") || msg.contains("unimplemented"))) {
                    hasImplementError = true;
                    break;
                }
            }

            // 即使诊断消息不匹配，也尝试分析源码中是否有 implements/extends 需要生成方法
            if (!hasImplementError) {
                // 检查源码中是否有 implements 或 extends 抽象类
                hasImplementError = source.matches("(?s).*class\\s+\\w+\\s+(?:extends|implements)\\s+.*");
            }

            if (hasImplementError) {
                String projectName = extractProjectName(uri);
                String[] projectClasspath = resolveProjectClasspath(projectName);
                List<JdtCoreService.MethodStub> stubs = jdtCoreService.generateMethodStubs(source, projectClasspath);

                for (JdtCoreService.MethodStub stub : stubs) {
                    CodeAction action = new CodeAction("生成未实现的方法 (" + stub.getContext() + ")");
                    action.setKind(CodeActionKind.QuickFix);
                    action.setDiagnostics(params.getContext().getDiagnostics());

                    // 计算插入位置的行列号
                    int offset = stub.getInsertOffset();
                    int line = 0, col = 0;
                    for (int i = 0; i < offset && i < source.length(); i++) {
                        if (source.charAt(i) == '\n') { line++; col = 0; }
                        else col++;
                    }

                    TextEdit edit = new TextEdit(
                            new Range(new Position(line, col), new Position(line, col)),
                            stub.getCode()
                    );

                    WorkspaceEdit wsEdit = new WorkspaceEdit();
                    Map<String, List<TextEdit>> changes = new HashMap<>();
                    changes.put(uri, Collections.singletonList(edit));
                    wsEdit.setChanges(changes);
                    action.setEdit(wsEdit);

                    actions.add(Either.forRight(action));
                }
            }

            // Lambda 补全：检测 FunctionalInterface = 的模式
            addLambdaCodeActions(actions, source, uri, params);

            // 自动 import：检测 "cannot be resolved" 类型错误，提供 import 修复
            addAutoImportActions(actions, source, uri, params);

            return actions;
        });
    }

    /**
     * 检测 Lambda 上下文，提供 Lambda 表达式代码操作。
     */
    private void addLambdaCodeActions(List<Either<Command, CodeAction>> actions,
                                       String source, String uri, CodeActionParams params) {
        Range range = params.getRange();
        int offset = computeOffset(source, range.getStart());

        // 向前查找 "TypeName varName =" 模式
        int lineStart = source.lastIndexOf('\n', Math.max(0, offset - 1));
        if (lineStart < 0) lineStart = 0;
        String lineText = source.substring(lineStart, Math.min(offset + 50, source.length()));

        java.util.regex.Pattern assignPattern = java.util.regex.Pattern.compile(
                "([A-Z]\\w*)(?:<[^>]*>)?\\s+\\w+\\s*=\\s*$");
        java.util.regex.Matcher m = assignPattern.matcher(lineText.substring(0, Math.min(lineText.length(), offset - lineStart)));
        if (m.find()) {
            String typeName = m.group(1);
            String projectName = extractProjectName(uri);
            String[] projectClasspath = resolveProjectClasspath(projectName);
            String lambda = jdtCoreService.generateLambdaSnippet(typeName, source, projectClasspath);
            if (lambda != null) {
                CodeAction action = new CodeAction("插入 Lambda 表达式: " + typeName);
                action.setKind(CodeActionKind.QuickFix);

                TextEdit edit = new TextEdit(
                        new Range(range.getStart(), range.getStart()),
                        lambda
                );
                WorkspaceEdit wsEdit = new WorkspaceEdit();
                Map<String, List<TextEdit>> changes = new HashMap<>();
                changes.put(uri, Collections.singletonList(edit));
                wsEdit.setChanges(changes);
                action.setEdit(wsEdit);

                actions.add(Either.forRight(action));
            }
        }
    }

    /**
     * 自动 import：从诊断中提取无法解析的类型名，在项目类索引中查找候选全限定名，
     * 提供 "Import xxx.yyy.ClassName" 的快速修复。
     */
    private void addAutoImportActions(List<Either<Command, CodeAction>> actions,
                                       String source, String uri, CodeActionParams params) {
        String projectName = extractProjectName(uri);
        String[] projectClasspath = resolveProjectClasspath(projectName);

        Set<String> alreadyOffered = new HashSet<>();

        for (Diagnostic d : params.getContext().getDiagnostics()) {
            String msg = d.getMessage();
            if (msg == null) continue;

            // ECJ 错误消息格式: "Xxx cannot be resolved to a type" 或 "Xxx cannot be resolved"
            String unresolvedType = null;
            java.util.regex.Matcher m;

            m = java.util.regex.Pattern.compile("(\\w+) cannot be resolved(?: to a type)?").matcher(msg);
            if (m.find()) {
                unresolvedType = m.group(1);
            }
            if (unresolvedType == null) {
                m = java.util.regex.Pattern.compile("(\\w+) is not accessible").matcher(msg);
                if (m.find()) unresolvedType = m.group(1);
            }

            if (unresolvedType == null || alreadyOffered.contains(unresolvedType)) continue;
            alreadyOffered.add(unresolvedType);

            // 查找候选全限定名（包括内存中打开的文档）
            List<String> candidates = jdtCoreService.findImportCandidates(unresolvedType, projectClasspath, openDocuments);
            if (candidates.isEmpty()) continue;

            // 检查是否已经 import 了
            for (String fullName : candidates) {
                if (source.contains("import " + fullName + ";")) continue;
                // 同包的不需要 import
                String currentPkg = extractPackage(source);
                String candidatePkg = fullName.contains(".")
                    ? fullName.substring(0, fullName.lastIndexOf('.')) : "";
                if (currentPkg.equals(candidatePkg)) continue;

                // 计算 import 插入位置
                int importLine = findImportInsertLine(source);

                CodeAction action = new CodeAction("Import " + fullName);
                action.setKind(CodeActionKind.QuickFix);
                action.setDiagnostics(Collections.singletonList(d));

                TextEdit edit = new TextEdit(
                    new Range(new Position(importLine, 0), new Position(importLine, 0)),
                    "import " + fullName + ";\n"
                );

                WorkspaceEdit wsEdit = new WorkspaceEdit();
                Map<String, List<TextEdit>> changes = new HashMap<>();
                changes.put(uri, Collections.singletonList(edit));
                wsEdit.setChanges(changes);
                action.setEdit(wsEdit);

                actions.add(Either.forRight(action));
            }
        }
    }

    /**
     * 从源码中提取 package 声明。
     */
    private String extractPackage(String source) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^\\s*package\\s+([\\w.]+)\\s*;", java.util.regex.Pattern.MULTILINE)
            .matcher(source);
        return m.find() ? m.group(1) : "";
    }

    /**
     * 调用 JDT Core 诊断并推送结果到客户端。
     */
    private void publishDiagnostics(String uri, String source) {
        if (client == null) return;

        String unitName = extractUnitName(uri);
        String projectName = extractProjectName(uri);
        String[] projectClasspath = resolveProjectClasspath(projectName);
        
        // 传递所有打开的文档，以便 ECJ 能够解析跨包引用
        List<IProblem> problems = jdtCoreService.diagnose(source, unitName, projectClasspath, openDocuments);

        List<Diagnostic> diagnostics = new ArrayList<>();
        for (IProblem problem : problems) {
            Diagnostic d = new Diagnostic();
            // IProblem 行号从 1 开始，LSP 从 0 开始
            int line = Math.max(0, problem.getSourceLineNumber() - 1);
            int startCol = Math.max(0, problem.getSourceStart());
            int endCol = Math.max(0, problem.getSourceEnd() + 1);

            // 将绝对偏移量转换为行内列号
            int lineStartOffset = getLineStartOffset(source, problem.getSourceLineNumber());
            int colStart = startCol - lineStartOffset;
            int colEnd = endCol - lineStartOffset;

            d.setRange(new Range(
                    new Position(line, Math.max(0, colStart)),
                    new Position(line, Math.max(0, colEnd))
            ));
            d.setMessage(problem.getMessage());
            d.setSeverity(problem.isError()
                    ? DiagnosticSeverity.Error
                    : DiagnosticSeverity.Warning);
            d.setSource("jdt-core");
            diagnostics.add(d);
        }

        client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
    }

    /**
     * 当 MCP 工具通过文件系统直接修改文件后，使 LSP 缓存和项目类索引失效。
     * 如果文件当前在编辑器中打开，从磁盘重新读取内容并推送诊断更新。
     */
    public void invalidateDocumentByFilePath(String absolutePath) {
        if (absolutePath == null) return;
        String workspacePath = Constants.workspacePath;
        if (workspacePath == null) return;

        String normalizedPath = absolutePath.replace('\\', '/');
        String normalizedWs = workspacePath.replace('\\', '/');

        if (!normalizedPath.startsWith(normalizedWs)) return;

        String relative = normalizedPath.substring(normalizedWs.length());
        if (relative.startsWith("/")) relative = relative.substring(1);

        // relative = "projectName/src/Main.java"
        int slash = relative.indexOf('/');
        if (slash <= 0) return;

        String projectName = relative.substring(0, slash);
        String projectRelativePath = relative.substring(slash + 1);

        // 构建 LSP URI: file:///workspace/{projectName}/relative/path
        String uri = "file:///workspace/" + projectName + "/" + projectRelativePath;

        // 如果文件在 openDocuments 中，更新内容并推送诊断
        if (openDocuments.containsKey(uri)) {
            try {
                String newContent = new String(
                    java.nio.file.Files.readAllBytes(new java.io.File(absolutePath).toPath()), "UTF-8");
                openDocuments.put(uri, newContent);
                publishDiagnostics(uri, newContent);
            } catch (IOException e) {
                openDocuments.remove(uri);
            }
        }

        // 刷新项目类索引
        jdtCoreService.refreshProjectIndex(projectName);
    }

    /**
     * 根据 Position (line, character) 计算源码中的绝对偏移量。
     */
    private int computeOffset(String source, Position position) {
        int line = 0;
        int offset = 0;
        while (offset < source.length() && line < position.getLine()) {
            if (source.charAt(offset) == '\n') {
                line++;
            }
            offset++;
        }
        return Math.min(offset + position.getCharacter(), source.length());
    }

    /**
     * 获取指定行（1-based）在源码中的起始偏移量。
     */
    private int getLineStartOffset(String source, int lineNumber) {
        int line = 1;
        int offset = 0;
        while (offset < source.length() && line < lineNumber) {
            if (source.charAt(offset) == '\n') {
                line++;
            }
            offset++;
        }
        return offset;
    }

    /**
     * 从 URI 中提取编译单元名称。
     * ECJ 需要相对于源码根的路径（如 com/sunway/test/Demo.java），
     * 这样才能正确匹配 package 声明并解析跨包引用。
     *
     * URI 格式: file:///workspace/{project}/src/com/sunway/test/Demo.java
     *           file:///workspace/{project}/src/main/java/com/sunway/test/Demo.java
     */
    private String extractUnitName(String uri) {
        String prefix = "file:///workspace/";
        if (uri.startsWith(prefix)) {
            String rest = uri.substring(prefix.length());
            // rest = project1/src/com/sunway/test/Demo.java
            int slash = rest.indexOf('/');
            if (slash > 0) {
                String pathInProject = rest.substring(slash + 1);
                // 去掉源码根前缀，保留包路径 + 文件名
                String[] sourceRoots = {"src/main/java/", "src/test/java/", "src/"};
                for (String root : sourceRoots) {
                    if (pathInProject.startsWith(root)) {
                        return pathInProject.substring(root.length());
                    }
                }
                // 没有匹配的源码根，返回项目内完整路径
                return pathInProject;
            }
        }
        // fallback: 只取文件名
        int lastSlash = uri.lastIndexOf('/');
        if (lastSlash >= 0) {
            return uri.substring(lastSlash + 1);
        }
        return uri;
    }

    private CompletionItemKind mapKind(JdtCoreService.CompletionProposal.Kind kind) {
        switch (kind) {
            case CLASS:    return CompletionItemKind.Class;
            case METHOD:   return CompletionItemKind.Method;
            case FIELD:    return CompletionItemKind.Field;
            case VARIABLE: return CompletionItemKind.Variable;
            case KEYWORD:  return CompletionItemKind.Keyword;
            default:       return CompletionItemKind.Text;
        }
    }

    /**
     * 找到 import 语句应该插入的行号。
     * 在 package 声明之后、已有 import 之后，或文件开头。
     */
    private int findImportInsertLine(String source) {
        String[] lines = source.split("\n");
        int insertLine = 0;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("package ")) {
                insertLine = i + 1;
            } else if (trimmed.startsWith("import ")) {
                insertLine = i + 1;
            }
        }
        return insertLine;
    }

    /**
     * 提供查找引用功能（References）。
     * 使用简单的文本搜索实现。
     */
    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFuture.supplyAsync(() -> {
            List<Location> locations = new ArrayList<>();
            String uri = params.getTextDocument().getUri();
            String source = openDocuments.getOrDefault(uri, "");
            touchDocument(uri); // 刷新访问时间
            if (source.isEmpty()) return locations;

            String projectName = extractProjectName(uri);
            if (projectName == null) return locations;

            // 获取光标处的单词
            Position pos = params.getPosition();
            int offset = computeOffset(source, pos);
            String word = getWordAt(source, offset);
            if (word == null || word.isEmpty() || isKeyword(word)) return locations;

            // 在项目目录中搜索所有 Java 文件
            String workspacePath = Constants.workspacePath;
            if (workspacePath == null) return locations;

            java.io.File projectDir = new java.io.File(workspacePath, projectName);
            if (!projectDir.exists()) return locations;

            Pattern refPattern = Pattern.compile("\\b" + Pattern.quote(word) + "\\b");
            searchReferences(projectDir, "", refPattern, projectName, locations);

            log.info("[LSP] references: found {} for '{}'", locations.size(), word);
            return locations;
        });
    }

    /**
     * 递归搜索项目中的 Java 文件，查找引用。
     */
    private void searchReferences(java.io.File dir, String relativePath, Pattern pattern,
                                   String projectName, List<Location> locations) {
        java.io.File[] files = dir.listFiles();
        if (files == null) return;

        for (java.io.File file : files) {
            if (file.isDirectory()) {
                searchReferences(file, relativePath + file.getName() + "/", pattern, projectName, locations);
            } else if (file.getName().endsWith(".java")) {
                try {
                    String content = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
                    String[] lines = content.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        Matcher m = pattern.matcher(lines[i]);
                        while (m.find()) {
                            int col = m.start() + 1; // 1-based column
                            Location loc = new Location();
                            loc.setUri("file:///workspace/" + projectName + "/" + relativePath + file.getName());
                            loc.setRange(new Range(new Position(i, col), new Position(i, col + m.group().length())));
                            locations.add(loc);
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    /**
     * 获取光标处的单词。
     */
    private String getWordAt(String source, int offset) {
        if (offset < 0 || offset >= source.length()) return null;
        int start = offset;
        int end = offset;
        while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) start--;
        while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) end++;
        return source.substring(start, end);
    }

    /**
     * 检查是否是 Java 关键字。
     */
    private boolean isKeyword(String word) {
        Set<String> keywords = new HashSet<>(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "true", "false", "null"
        ));
        return keywords.contains(word);
    }

    // ========== lsp4j 0.14.0+ required methods ==========

    /**
     * 悬停提示 (Hover)。
     */
    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 跳转定义 (Go to Definition)。
     */
    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
    }

    /**
     * 跳转实现 (Go to Implementation)。
     */
    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(
            ImplementationParams params) {
        return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
    }
}
