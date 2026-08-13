package com.github.obhen233.compiler.service;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.entity.IdeSetting;
import com.github.obhen233.compiler.repository.IdeSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ClasspathBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ClasspathBuilder.class);

    @Autowired(required = false)
    private IdeSettingRepository settingRepo;

    @Autowired(required = false)
    private Environment springEnv;

    // Maven 多模块 pom.xml 中提取 module 名称的正则
    public static final Pattern MAVEN_MODULE_PATTERN = Pattern.compile("<module>\\s*(.*?)\\s*</module>");

    // Gradle settings.gradle 中提取 include 语句的正则
    public static final Pattern GRADLE_INCLUDE_PATTERN = Pattern.compile("include\\s+(['\"])(.*?)\\1");

    public String detectProjectType(File projectDir) {
        if (new File(projectDir, "pom.xml").exists()) return "maven";
        if (new File(projectDir, "build.gradle").exists()) return "gradle";
        return "plain";
    }

    /**
     * 获取配置的 Java Home，使用与 JavaCompileServiceImpl 相同的解析模式。
     * 优先级: SQLite > application.properties > 环境变量 > 系统属性
     */
    public String getConfiguredJavaHome() {
        String javaHome = getSettingValue("javaHome");
        if (javaHome != null && !javaHome.isEmpty()) {
            return javaHome;
        }
        return System.getProperty("java.home");
    }

    /**
     * 从 IDE Settings 读取配置值。
     * 优先级: SQLite > application.properties > 系统环境变量
     */
    public String getSettingValue(String key) {
        // 1. 从 SQLite 读取
        if (settingRepo != null) {
            try {
                String val = settingRepo.findById(key).map(IdeSetting::getValue).orElse(null);
                if (val != null && !val.trim().isEmpty()) return val.trim();
            } catch (Exception e) {
                logger.debug("Failed to read {} from SQLite: {}", key, e.getMessage());
            }
        }
        // 2. 从 application.properties 读取
        if (springEnv != null) {
            String propKey = settingKeyToPropertyKey(key);
            if (propKey != null) {
                String val = springEnv.getProperty(propKey);
                if (val != null && !val.trim().isEmpty()) return val.trim();
            }
        }
        // 3. 从系统环境变量回退读取
        return resolveFromEnv(key);
    }

    public String settingKeyToPropertyKey(String key) {
        if ("javaHome".equals(key)) return "ide.java.home";
        if ("mavenHome".equals(key)) return "ide.maven.home";
        if ("mavenUserSettings".equals(key)) return "ide.maven.user.settings";
        if ("mavenLocalRepository".equals(key)) return "ide.maven.local.repository";
        if ("gradleUserHome".equals(key)) return "ide.gradle.user.home";
        if ("gitPath".equals(key)) return "ide.git.path";
        if ("svnPath".equals(key)) return "ide.svn.path";
        return null;
    }

    public String resolveFromEnv(String key) {
        if ("javaHome".equals(key)) {
            String jh = System.getProperty("java.home");
            if (jh != null && !jh.isEmpty()) return jh;
            return System.getenv("JAVA_HOME");
        }
        if ("mavenHome".equals(key)) {
            String m2 = System.getenv("M2_HOME");
            if (m2 != null && !m2.isEmpty()) return m2;
            return System.getenv("MAVEN_HOME");
        }
        if ("gradleUserHome".equals(key)) {
            return System.getenv("GRADLE_USER_HOME");
        }
        return null;
    }

    /**
     * 检测 Maven 多模块项目的子模块目录列表
     */
    public List<File> getMavenChildModules(File projectDir) {
        List<File> modules = new ArrayList<>();
        File pomFile = new File(projectDir, "pom.xml");
        if (!pomFile.exists()) return modules;
        try {
            String content = new String(Files.readAllBytes(pomFile.toPath()), "UTF-8");
            if (!content.contains("<modules>")) return modules;
            Matcher matcher = MAVEN_MODULE_PATTERN.matcher(content);
            while (matcher.find()) {
                String moduleName = matcher.group(1).trim();
                File moduleDir = new File(projectDir, moduleName);
                if (moduleDir.exists() && moduleDir.isDirectory()) {
                    modules.add(moduleDir);
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to parse Maven multi-module POM: {}", e.getMessage());
        }
        return modules;
    }

    /**
     * 检测 Gradle 多项目的子项目目录列表
     */
    public List<File> getGradleChildProjects(File projectDir) {
        List<File> projects = new ArrayList<>();
        File settingsFile = new File(projectDir, "settings.gradle");
        if (!settingsFile.exists()) {
            settingsFile = new File(projectDir, "settings.gradle.kts");
        }
        if (!settingsFile.exists()) return projects;
        try {
            String content = new String(Files.readAllBytes(settingsFile.toPath()), "UTF-8");
            Matcher matcher = GRADLE_INCLUDE_PATTERN.matcher(content);
            while (matcher.find()) {
                String modulePath = matcher.group(2);
                // Gradle 使用冒号分隔子项目路径（如 ':subproject:moduleA' -> subproject/moduleA）
                String dirPath = modulePath.replace(':', File.separatorChar);
                // 移除开头的路径分隔符
                if (dirPath.startsWith(File.separator)) {
                    dirPath = dirPath.substring(1);
                }
                File moduleDir = new File(projectDir, dirPath);
                if (moduleDir.exists() && moduleDir.isDirectory()) {
                    projects.add(moduleDir);
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to parse Gradle settings: {}", e.getMessage());
        }
        return projects;
    }

    /**
     * 添加模块的编译输出目录到 classpath
     */
    public void addModuleOutputDir(File moduleDir, List<String> cpEntries) {
        // Maven 输出目录
        File mavenOutput = new File(moduleDir, "target" + File.separator + "classes");
        if (mavenOutput.exists()) {
            cpEntries.add(mavenOutput.getAbsolutePath());
        }
        // Gradle 输出目录
        File gradleOutput = new File(moduleDir, "build" + File.separator + "classes"
            + File.separator + "java" + File.separator + "main");
        if (gradleOutput.exists()) {
            cpEntries.add(gradleOutput.getAbsolutePath());
        }
    }

    /** Build classpath string for running the project */
    public String buildClasspath(File projectDir, String projectType) throws IOException {
        String classpath = "";
        List<String> cpEntries = new ArrayList<>();

        // 检测是否为多模块项目
        List<File> childModules = getMavenChildModules(projectDir);
        boolean isMultiModule = !childModules.isEmpty();
        if (childModules.isEmpty()) {
            childModules = getGradleChildProjects(projectDir);
            isMultiModule = !childModules.isEmpty();
        }

        if ("maven".equals(projectType)) {
            File cpFile = new File(projectDir, "target/.cp");
            if (cpFile.exists()) {
                String cp = new String(Files.readAllBytes(cpFile.toPath()), "UTF-8").trim();
                if (!cp.isEmpty()) {
                    for (String entry : cp.split(Pattern.quote(File.pathSeparator))) {
                        if (!entry.trim().isEmpty()) {
                            cpEntries.add(entry.trim());
                        }
                    }
                }
            }
            // 多模块：添加各子模块的编译输出目录 AND 依赖 AND lib/*.jar
            if (isMultiModule) {
                for (File module : childModules) {
                    addModuleOutputDir(module, cpEntries);
                    // Also read the sub-module's .cp file for its Maven dependencies
                    File moduleCpFile = new File(module, "target/.cp");
                    if (moduleCpFile.exists()) {
                        String moduleCp = new String(Files.readAllBytes(moduleCpFile.toPath()), "UTF-8").trim();
                        if (!moduleCp.isEmpty()) {
                            for (String entry : moduleCp.split(Pattern.quote(File.pathSeparator))) {
                                if (!entry.trim().isEmpty()) {
                                    cpEntries.add(entry.trim());
                                }
                            }
                        }
                    }
                    // Also add sub-module's lib/*.jar
                    File moduleLibDir = new File(module, "lib");
                    if (moduleLibDir.exists() && moduleLibDir.isDirectory()) {
                        File[] moduleJars = moduleLibDir.listFiles((dir, name) -> name.endsWith(".jar"));
                        if (moduleJars != null) {
                            for (File jar : moduleJars) {
                                cpEntries.add(jar.getAbsolutePath());
                            }
                        }
                    }
                }
            }
            // 添加 lib/*.jar（手动引入的依赖）
            File libDir = new File(projectDir, "lib");
            if (libDir.exists() && libDir.isDirectory()) {
                File[] jars = libDir.listFiles((dir, name) -> name.endsWith(".jar"));
                if (jars != null) {
                    for (File jar : jars) {
                        cpEntries.add(jar.getAbsolutePath());
                    }
                }
            }
            // target/classes already contains compiled classes AND copied resources (Maven copies
            // src/main/resources into target/classes). Adding src/main/resources would duplicate
            // config files (e.g., struts.xml, application.properties) causing framework init errors.
            File targetClasses = new File(projectDir, "target/classes");
            if (targetClasses.exists()) {
                cpEntries.add(0, targetClasses.getAbsolutePath());
            }
        } else if ("gradle".equals(projectType)) {
            File cpFile = new File(projectDir, "build/.cp");
            if (cpFile.exists()) {
                String cp = new String(Files.readAllBytes(cpFile.toPath()), "UTF-8").trim();
                if (!cp.isEmpty()) {
                    for (String entry : cp.split(Pattern.quote(File.pathSeparator))) {
                        if (!entry.trim().isEmpty()) {
                            cpEntries.add(entry.trim());
                        }
                    }
                }
            }
            // 多模块：添加各子模块的编译输出目录 AND 依赖 AND lib/*.jar
            if (isMultiModule) {
                for (File module : childModules) {
                    addModuleOutputDir(module, cpEntries);
                    // Also read the sub-module's .cp file for its Gradle dependencies
                    File moduleCpFile = new File(module, "build/.cp");
                    if (moduleCpFile.exists()) {
                        String moduleCp = new String(Files.readAllBytes(moduleCpFile.toPath()), "UTF-8").trim();
                        if (!moduleCp.isEmpty()) {
                            for (String entry : moduleCp.split(Pattern.quote(File.pathSeparator))) {
                                if (!entry.trim().isEmpty()) {
                                    cpEntries.add(entry.trim());
                                }
                            }
                        }
                    }
                    // Also add sub-module's lib/*.jar
                    File moduleLibDir = new File(module, "lib");
                    if (moduleLibDir.exists() && moduleLibDir.isDirectory()) {
                        File[] moduleJars = moduleLibDir.listFiles((dir, name) -> name.endsWith(".jar"));
                        if (moduleJars != null) {
                            for (File jar : moduleJars) {
                                cpEntries.add(jar.getAbsolutePath());
                            }
                        }
                    }
                }
            }
            // 添加 lib/*.jar（手动引入的依赖）
            File libDir = new File(projectDir, "lib");
            if (libDir.exists() && libDir.isDirectory()) {
                File[] jars = libDir.listFiles((dir, name) -> name.endsWith(".jar"));
                if (jars != null) {
                    for (File jar : jars) {
                        cpEntries.add(jar.getAbsolutePath());
                    }
                }
            }
            File buildClasses = new File(projectDir, "build/classes/java/main");
            if (!buildClasses.exists()) buildClasses = new File(projectDir, "build/classes/main");
            if (buildClasses.exists()) {
                cpEntries.add(0, buildClasses.getAbsolutePath());
            }
            File resources = new File(projectDir, "build/resources/main");
            if (!resources.exists()) resources = new File(projectDir, "src/main/resources");
            if (resources.exists()) {
                cpEntries.add(0, resources.getAbsolutePath());
            }
        } else {
            File targetClasses = new File(projectDir, "target/classes");
            if (targetClasses.exists()) cpEntries.add(targetClasses.getAbsolutePath());
            File libDir = new File(projectDir, "lib");
            if (libDir.exists()) {
                File[] jars = libDir.listFiles(new FilenameFilter() {
                    @Override
                    public boolean accept(File dir, String name) {
                        return name.endsWith(".jar");
                    }
                });
                if (jars != null) {
                    for (File jar : jars) {
                        cpEntries.add(jar.getAbsolutePath());
                    }
                }
            }
            File srcDir = new File(projectDir, "src");
            if (srcDir.exists()) {
                cpEntries.add(srcDir.getAbsolutePath());
            }
        }
        return cpEntries.stream().distinct().collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    /** Build classpath as array for code generation / JDT use */
    public String[] buildClasspathArray(String projectName) {
        File projectDir = new File(Constants.workspacePath, projectName);
        if (!projectDir.exists()) return null;
        List<String> paths = new ArrayList<>();
        // lib/*.jar
        File libDir = new File(projectDir, "lib");
        if (libDir.exists() && libDir.isDirectory()) {
            File[] jars = libDir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.endsWith(".jar");
                }
            });
            if (jars != null) {
                for (File jar : jars) {
                    paths.add(jar.getAbsolutePath());
                }
            }
        }
        // Multi-module: add sub-modules' lib/*.jar first
        List<File> childModules = getMavenChildModules(projectDir);
        boolean isMultiModule = !childModules.isEmpty();
        if (childModules.isEmpty()) {
            childModules = getGradleChildProjects(projectDir);
            isMultiModule = !childModules.isEmpty();
        }
        if (isMultiModule) {
            for (File module : childModules) {
                File moduleLibDir = new File(module, "lib");
                if (moduleLibDir.exists() && moduleLibDir.isDirectory()) {
                    File[] moduleJars = moduleLibDir.listFiles((dir, name) -> name.endsWith(".jar"));
                    if (moduleJars != null) {
                        for (File jar : moduleJars) {
                            paths.add(jar.getAbsolutePath());
                        }
                    }
                }
            }
        }
        // Maven: target/.cp
        File mvnCp = new File(projectDir, "target/.cp");
        if (mvnCp.exists()) {
            try {
                String cp = new String(Files.readAllBytes(mvnCp.toPath()), "UTF-8").trim();
                if (!cp.isEmpty()) {
                    for (String entry : cp.split(Pattern.quote(File.pathSeparator))) {
                        if (!entry.trim().isEmpty() && new File(entry.trim()).exists()) {
                            paths.add(entry.trim());
                        }
                    }
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        // Multi-module: add sub-modules' Maven .cp dependencies
        if (isMultiModule) {
            for (File module : childModules) {
                File moduleCpFile = new File(module, "target/.cp");
                if (moduleCpFile.exists()) {
                    try {
                        String moduleCp = new String(Files.readAllBytes(moduleCpFile.toPath()), "UTF-8").trim();
                        if (!moduleCp.isEmpty()) {
                            for (String entry : moduleCp.split(Pattern.quote(File.pathSeparator))) {
                                if (!entry.trim().isEmpty() && new File(entry.trim()).exists()) {
                                    paths.add(entry.trim());
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            }
        }
        // Gradle: build/.cp
        File gradleCp = new File(projectDir, "build/.cp");
        if (gradleCp.exists()) {
            try {
                String cp = new String(Files.readAllBytes(gradleCp.toPath()), "UTF-8").trim();
                if (!cp.isEmpty()) {
                    for (String entry : cp.split(Pattern.quote(File.pathSeparator))) {
                        if (!entry.trim().isEmpty() && new File(entry.trim()).exists()) {
                            paths.add(entry.trim());
                        }
                    }
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        // Multi-module: add sub-modules' Gradle .cp dependencies
        if (isMultiModule) {
            for (File module : childModules) {
                File moduleCpFile = new File(module, "build/.cp");
                if (moduleCpFile.exists()) {
                    try {
                        String moduleCp = new String(Files.readAllBytes(moduleCpFile.toPath()), "UTF-8").trim();
                        if (!moduleCp.isEmpty()) {
                            for (String entry : moduleCp.split(Pattern.quote(File.pathSeparator))) {
                                if (!entry.trim().isEmpty() && new File(entry.trim()).exists()) {
                                    paths.add(entry.trim());
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            }
        }
        // Source directories
        File srcMainJava = new File(projectDir, "src/main/java");
        if (srcMainJava.exists()) {
            paths.add(srcMainJava.getAbsolutePath());
        } else {
            File srcDir = new File(projectDir, "src");
            if (srcDir.exists()) {
                paths.add(srcDir.getAbsolutePath());
            }
        }
        // Compiled output
        File targetClasses = new File(projectDir, "target/classes");
        if (targetClasses.exists()) {
            paths.add(targetClasses.getAbsolutePath());
        }
        File buildClasses = new File(projectDir, "build/classes/java/main");
        if (buildClasses.exists()) {
            paths.add(buildClasses.getAbsolutePath());
        }
        // Multi-module: add sub-modules' compiled output
        if (isMultiModule) {
            for (File module : childModules) {
                File moduleTargetClasses = new File(module, "target/classes");
                if (moduleTargetClasses.exists()) {
                    paths.add(moduleTargetClasses.getAbsolutePath());
                }
                File moduleBuildClasses = new File(module, "build/classes/java/main");
                if (moduleBuildClasses.exists()) {
                    paths.add(moduleBuildClasses.getAbsolutePath());
                }
            }
        }
        return paths.isEmpty() ? null : paths.toArray(new String[0]);
    }

    // ==================== Spring Boot & Multi-module Support ====================

    /**
     * Check if the project is a Spring Boot project.
     */
    public boolean isSpringBootProject(File projectDir) {
        File pomFile = new File(projectDir, "pom.xml");
        if (!pomFile.exists()) return false;
        try {
            String content = new String(Files.readAllBytes(pomFile.toPath()), "UTF-8");
            return content.contains("spring-boot-starter") ||
                   content.contains("org.springframework.boot");
        } catch (Exception e) {
            logger.debug("Failed to check Spring Boot: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get all source roots for a multi-module project.
     */
    public List<String> getAllSourceRoots(File projectDir) {
        List<String> roots = new ArrayList<>();
        collectSourceRoots(projectDir, roots, new java.util.HashSet<>());
        return roots;
    }

    private void collectSourceRoots(File dir, List<String> roots, java.util.Set<String> visited) {
        String absPath = dir.getAbsolutePath();
        if (visited.contains(absPath)) return;
        visited.add(absPath);

        // Add source root if exists
        File srcMainJava = new File(dir, "src/main/java");
        if (srcMainJava.exists()) {
            roots.add(srcMainJava.getAbsolutePath());
        }
        File srcMainResources = new File(dir, "src/main/resources");
        if (srcMainResources.exists()) {
            roots.add(srcMainResources.getAbsolutePath());
        }

        // Check for Maven modules
        List<File> modules = getMavenChildModules(dir);
        for (File module : modules) {
            collectSourceRoots(module, roots, visited);
        }

        // Check for Gradle projects
        if (modules.isEmpty()) {
            List<File> gradleProjects = getGradleChildProjects(dir);
            for (File project : gradleProjects) {
                collectSourceRoots(project, roots, visited);
            }
        }
    }

    /**
     * Build full classpath including all modules and dependencies.
     */
    public String buildFullClasspath(File projectDir, String projectType) throws IOException {
        List<String> cpEntries = new ArrayList<>();
        List<File> childModules = getMavenChildModules(projectDir);
        boolean isMultiModule = !childModules.isEmpty();
        if (childModules.isEmpty()) {
            childModules = getGradleChildProjects(projectDir);
            isMultiModule = !childModules.isEmpty();
        }

        if ("maven".equals(projectType)) {
            // Read cached classpath from target/.cp
            File cpFile = new File(projectDir, "target/.cp");
            if (cpFile.exists()) {
                String cp = new String(Files.readAllBytes(cpFile.toPath()), "UTF-8").trim();
                if (!cp.isEmpty()) {
                    for (String entry : cp.split(Pattern.quote(File.pathSeparator))) {
                        if (!entry.trim().isEmpty()) {
                            cpEntries.add(entry.trim());
                        }
                    }
                }
            }
            // Add main project output — target/classes already contains resources (copied by Maven)
            File targetClasses = new File(projectDir, "target/classes");
            if (targetClasses.exists()) {
                cpEntries.add(0, targetClasses.getAbsolutePath());
            }
            // Add multi-module outputs
            if (isMultiModule) {
                for (File module : childModules) {
                    addModuleOutputDir(module, cpEntries);
                }
            }
        } else if ("gradle".equals(projectType)) {
            // Read cached classpath from build/.cp
            File cpFile = new File(projectDir, "build/.cp");
            if (cpFile.exists()) {
                String cp = new String(Files.readAllBytes(cpFile.toPath()), "UTF-8").trim();
                if (!cp.isEmpty()) {
                    for (String entry : cp.split(Pattern.quote(File.pathSeparator))) {
                        if (!entry.trim().isEmpty()) {
                            cpEntries.add(entry.trim());
                        }
                    }
                }
            }
            // Add main project output
            File buildClasses = new File(projectDir, "build/classes/java/main");
            if (!buildClasses.exists()) buildClasses = new File(projectDir, "build/classes/main");
            if (buildClasses.exists()) {
                cpEntries.add(0, buildClasses.getAbsolutePath());
            }
            File resources = new File(projectDir, "build/resources/main");
            if (!resources.exists()) resources = new File(projectDir, "src/main/resources");
            if (resources.exists()) {
                cpEntries.add(0, resources.getAbsolutePath());
            }
            // Add multi-module outputs
            if (isMultiModule) {
                for (File module : childModules) {
                    addModuleOutputDir(module, cpEntries);
                }
            }
        } else {
            // Plain project
            File targetClasses = new File(projectDir, "target/classes");
            if (targetClasses.exists()) cpEntries.add(targetClasses.getAbsolutePath());
            File libDir = new File(projectDir, "lib");
            if (libDir.exists()) {
                File[] jars = libDir.listFiles((dir, name) -> name.endsWith(".jar"));
                if (jars != null) {
                    for (File jar : jars) {
                        cpEntries.add(jar.getAbsolutePath());
                    }
                }
            }
        }
        return cpEntries.stream().distinct().collect(Collectors.joining(File.pathSeparator));
    }

    /**
     * Resolve Maven classpath by running mvn dependency:build-classpath.
     * This generates the target/.cp file and returns the classpath.
     * @return the classpath string, or null if resolution failed
     */
    public String resolveMavenClasspath(String projectName) {
        File projectDir = new File(Constants.workspacePath, projectName);
        if (!projectDir.exists() || !new File(projectDir, "pom.xml").exists()) {
            return null;
        }

        try {
            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
            String mvnCmd = getMavenCommand();
            File cpFile = new File(projectDir, "target" + File.separator + ".cp");

            List<String> command = new ArrayList<>();
            command.add(mvnCmd);
            command.add("dependency:build-classpath");
            command.add("-Dmdep.outputFile=" + cpFile.getAbsolutePath());
            command.add("-B");
            command.add("-q");

            // Add Maven settings if configured
            String mavenSettings = getMavenUserSettings();
            if (mavenSettings != null && !mavenSettings.trim().isEmpty()) {
                command.add("-s");
                command.add(mavenSettings.trim());
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectDir);
            pb.redirectErrorStream(true);
            pb.environment().put("JAVA_HOME", getConfiguredJavaHome());

            logger.info("Running Maven classpath resolution: {}", String.join(" ", command));
            Process process = pb.start();

            // Wait for completion (max 5 minutes for large projects)
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                logger.error("Maven classpath resolution timed out for {}", projectName);
                return null;
            }

            int exitCode = process.exitValue();
            if (exitCode == 0 && cpFile.exists()) {
                String classpath = new String(Files.readAllBytes(cpFile.toPath()), "UTF-8").trim();
                logger.info("Maven classpath resolved: {} entries for {}", classpath.split(File.pathSeparator).length, projectName);
                return classpath;
            } else {
                logger.error("Maven classpath resolution failed (exit={}) for {}", exitCode, projectName);
                return null;
            }
        } catch (Exception e) {
            logger.error("Failed to resolve Maven classpath: {}", e.getMessage());
            return null;
        }
    }

    private String getMavenCommand() {
        String mvnHome = System.getenv("MAVEN_HOME");
        if (mvnHome == null || mvnHome.isEmpty()) {
            mvnHome = System.getenv("M2_HOME");
        }
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        if (mvnHome != null && !mvnHome.isEmpty()) {
            String mvnCmd = mvnHome + File.separator + "bin" + File.separator + (isWin ? "mvn.cmd" : "mvn");
            if (new File(mvnCmd).exists()) {
                return mvnCmd;
            }
        }
        return isWin ? "mvn.cmd" : "mvn";
    }

    /** 读取 .cp 文件中的 classpath 条目 */
    public List<String> readCpFile(File cpFile) {
        try {
            String content = new String(Files.readAllBytes(cpFile.toPath()), "UTF-8").trim();
            if (content.isEmpty()) return java.util.Collections.emptyList();
            List<String> result = new ArrayList<>();
            for (String entry : content.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.trim().isEmpty() && new File(entry.trim()).exists()) {
                    result.add(entry.trim());
                }
            }
            return result;
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    /** 查找 Maven 命令路径 */
    public String findMvnCommand(File projectDir, boolean isWin) {
        File mvnw = new File(projectDir, isWin ? "mvnw.cmd" : "mvnw");
        if (mvnw.exists()) return mvnw.getAbsolutePath();
        String mavenHome = getSettingValue("mavenHome");
        if (mavenHome != null && !mavenHome.trim().isEmpty()) {
            File mvnBin = new File(mavenHome, "bin" + File.separator + (isWin ? "mvn.cmd" : "mvn"));
            if (mvnBin.exists()) return mvnBin.getAbsolutePath();
        }
        return isWin ? "mvn.cmd" : "mvn";
    }

    /** 查找 Gradle 命令路径 */
    public String findGradleCommand(File projectDir, boolean isWin) {
        File gradlew = new File(projectDir, isWin ? "gradlew.bat" : "gradlew");
        if (gradlew.exists()) return gradlew.getAbsolutePath();
        String gradleHome = getSettingValue("gradleUserHome");
        if (gradleHome != null && !gradleHome.trim().isEmpty()) {
            File gradleBin = new File(gradleHome, "bin" + File.separator + (isWin ? "gradle.bat" : "gradle"));
            if (gradleBin.exists()) return gradleBin.getAbsolutePath();
        }
        return isWin ? "gradle.bat" : "gradle";
    }

    /** 已知的注解处理器 jar 名称模式 */
    private static final String[] ANNOTATION_PROCESSOR_PATTERNS = {
        "lombok", "mapstruct-processor", "auto-value", "dagger-compiler",
        "auto-service", "immutables-value"
    };

    /**
     * 从 classpath 和项目目录中检测注解处理器 jar。
     * 返回用 File.pathSeparator 分隔的处理器路径字符串。
     */
    public String detectAnnotationProcessors(String classpath, File projectDir) {
        List<String> processors = new ArrayList<>();

        // 1. 从 classpath 条目中查找
        if (classpath != null && !classpath.isEmpty()) {
            for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                String name = new File(entry.trim()).getName().toLowerCase();
                for (String pattern : ANNOTATION_PROCESSOR_PATTERNS) {
                    if (name.contains(pattern) && name.endsWith(".jar")) {
                        processors.add(entry.trim());
                        break;
                    }
                }
            }
        }

        // 2. 从项目 lib/ 目录查找
        File libDir = new File(projectDir, "lib");
        if (libDir.exists() && libDir.isDirectory()) {
            File[] jars = libDir.listFiles((d, n) -> {
                String lower = n.toLowerCase();
                for (String pattern : ANNOTATION_PROCESSOR_PATTERNS) {
                    if (lower.contains(pattern) && lower.endsWith(".jar")) return true;
                }
                return false;
            });
            if (jars != null) {
                for (File jar : jars) {
                    if (!processors.contains(jar.getAbsolutePath())) {
                        processors.add(jar.getAbsolutePath());
                    }
                }
            }
        }

        if (!processors.isEmpty()) {
            logger.info("Detected annotation processors: {}", processors);
        }
        return processors.stream().collect(Collectors.joining(File.pathSeparator));
    }

    private String getMavenUserSettings() {
        if (settingRepo != null) {
            try {
                IdeSetting setting = settingRepo.findById("mavenUserSettings").orElse(null);
                if (setting != null && setting.getValue() != null && !setting.getValue().trim().isEmpty()) {
                    return setting.getValue().trim();
                }
            } catch (Exception e) {
                logger.debug("Failed to get Maven settings: {}", e.getMessage());
            }
        }
        return null;
    }

}
