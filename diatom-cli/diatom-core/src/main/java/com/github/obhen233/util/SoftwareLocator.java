package com.github.obhen233.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 软件安装路径查找器
 * 遵循：环境变量 > PATH搜索 > 默认目录扫描 的优先级
 */
public class SoftwareLocator {
    private static final Logger logger = LoggerFactory.getLogger(SoftwareLocator.class);
    private static final int SEARCH_DEPTH = 4;

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    // Windows 默认安装目录
    private static final List<String> WINDOWS_SEARCH_ROOTS = Arrays.asList(
        "C:\\Program Files",
        "C:\\Program Files (x86)",
        "C:\\Users"
    );

    // Unix 默认安装目录
    private static final List<String> UNIX_SEARCH_ROOTS = Arrays.asList(
        "/usr/local",
        "/usr",
        "/opt",
        "/home"
    );

    /**
     * 查找可执行文件的安装根目录
     * @param executableName 可执行文件名（不带扩展名）
     * @return 安装根目录路径
     */
    public static Optional<Path> findInstallation(String executableName) {
        Optional<Path> result;

        // 第一步：环境变量查找
        result = findByEnvironmentVariable(executableName);
        if (result.isPresent()) {
            logger.debug("Found {} via environment variable: {}", executableName, result.get());
            return result;
        }

        // 第二步：PATH搜索
        result = findByPath(executableName);
        if (result.isPresent()) {
            logger.debug("Found {} via PATH: {}", executableName, result.get());
            return result;
        }

        // 第三步：默认目录扫描
        result = findByDefaultLocations(executableName);
        if (result.isPresent()) {
            logger.debug("Found {} via default locations: {}", executableName, result.get());
            return result;
        }

        logger.debug("Could not find installation for: {}", executableName);
        return Optional.empty();
    }

    /**
     * 通过环境变量查找
     */
    private static Optional<Path> findByEnvironmentVariable(String executableName) {
        // 特殊映射：可执行文件名 -> 环境变量名
        String envVar = mapToEnvironmentVariable(executableName);
        if (envVar != null) {
            String value = System.getenv(envVar);
            if (value != null && !value.isEmpty()) {
                Path path = Paths.get(value);
                if (Files.isDirectory(path)) {
                    return Optional.of(path);
                }
            }
        }

        // 尝试通用的 HOME 或路径环境变量
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] paths = pathEnv.split(IS_WINDOWS ? ";" : ":");
            String exeName = IS_WINDOWS ? executableName + ".exe" : executableName;
            for (String p : paths) {
                Path fullPath = Paths.get(p, exeName);
                if (Files.exists(fullPath)) {
                    return extractRootDirectory(fullPath);
                }
            }
        }

        return Optional.empty();
    }

    /**
     * 通过 which/where 命令在 PATH 中查找
     */
    private static Optional<Path> findByPath(String executableName) {
        try {
            String command = IS_WINDOWS ? "where" : "which";
            ProcessBuilder pb = new ProcessBuilder(command, executableName);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String firstLine = reader.readLine();
                    if (firstLine != null && !firstLine.trim().isEmpty()) {
                        Path execPath = Paths.get(firstLine.trim());
                        if (Files.exists(execPath)) {
                            return extractRootDirectory(execPath);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to find {} via which/where: {}", executableName, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * 在默认安装目录中扫描
     */
    private static Optional<Path> findByDefaultLocations(String executableName) {
        String exeName = IS_WINDOWS ? executableName + ".exe" : executableName;
        List<String> searchRoots = IS_WINDOWS ? WINDOWS_SEARCH_ROOTS : UNIX_SEARCH_ROOTS;

        for (String root : searchRoots) {
            Path rootPath = Paths.get(root);
            if (!Files.isDirectory(rootPath)) {
                continue;
            }

            try (Stream<Path> stream = Files.walk(rootPath, SEARCH_DEPTH)) {
                Optional<Path> found = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(exeName))
                    .findFirst();

                if (found.isPresent()) {
                    return extractRootDirectory(found.get());
                }
            } catch (Exception e) {
                logger.debug("Error scanning {}: {}", root, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * 从可执行文件路径提取安装根目录
     * 典型结构: /usr/bin/git -> /usr
     *          C:\Program Files\Git\bin\bash.exe -> C:\Program Files\Git
     */
    private static Optional<Path> extractRootDirectory(Path execPath) {
        if (execPath == null || !Files.exists(execPath)) {
            return Optional.empty();
        }

        Path parent = execPath.getParent();
        if (parent == null) {
            return Optional.empty();
        }

        // 如果在 bin 目录下，向上两级获取根目录
        if (parent.getFileName() != null &&
            "bin".equals(parent.getFileName().toString().toLowerCase())) {
            Path grandparent = parent.getParent();
            if (grandparent != null) {
                return Optional.of(grandparent);
            }
        }

        // 否则返回父目录
        return Optional.of(parent);
    }

    /**
     * 映射可执行文件名到环境变量名
     */
    private static String mapToEnvironmentVariable(String executableName) {
        switch (executableName.toLowerCase()) {
            case "java": case "javac": return "JAVA_HOME";
            case "git": return "GIT_HOME";
            case "mvn": return "MAVEN_HOME";
            case "gradle": return "GRADLE_HOME";
            case "node": return "NODE_HOME";
            case "python": case "python3": return "PYTHON_HOME";
            case "go": return "GOROOT";
            default: return null;
        }
    }

    /**
     * 在 Windows 上检测 bash 是否存在并识别类型
     * @return ShellInfo 或空
     */
    public static Optional<ShellInfo> findBashOnWindows() {
        if (!IS_WINDOWS) {
            return Optional.empty();
        }

        // 先尝试通过 PATH 找到 bash 可执行文件路径
        Optional<Path> bashExecPath = findExecutablePath("bash");
        if (bashExecPath.isPresent()) {
            String pathStr = bashExecPath.get().toString();
            String type = identifyBashType(pathStr.toLowerCase());
            if (type != null) {
                return Optional.of(new ShellInfo(type, bashExecPath.get().toString()));
            }
        }
        
        // 如果 PATH 中没找到，尝试在常见安装目录中查找
        Optional<ShellInfo> installedShell = findInstalledShell();
        if (installedShell.isPresent()) {
            return installedShell;
        }

        // WSL 检测
        Optional<ShellInfo> wslInfo = detectWSL();
        if (wslInfo.isPresent()) {
            return wslInfo;
        }

        return Optional.empty();
    }
    
    /**
     * 在常见安装目录中查找 MinGW、Cygwin、Git Bash 等
     * @return ShellInfo 或空
     */
    private static Optional<ShellInfo> findInstalledShell() {
        // MinGW64/MSYS2 常见安装路径
        List<String> mingwPaths = Arrays.asList(
            "C:\\msys64\\usr\\bin\\bash.exe",
            "C:\\msys32\\usr\\bin\\bash.exe",
            "C:\\mingw64\\usr\\bin\\bash.exe",
            "C:\\mingw32\\usr\\bin\\bash.exe",
            System.getProperty("user.home", "") + "\\msys64\\usr\\bin\\bash.exe",
            System.getProperty("user.home", "") + "\\scoop\\apps\\msys2\\current\\usr\\bin\\bash.exe"
        );
        
        for (String path : mingwPaths) {
            Path bashPath = Paths.get(path);
            if (Files.exists(bashPath)) {
                String type = identifyBashType(path.toLowerCase());
                logger.info("Found shell in MinGW/MSYS2 path: {} (type: {})", path, type);
                return Optional.of(new ShellInfo(type, path));
            }
        }
        
        // Cygwin 常见安装路径
        List<String> cygwinPaths = Arrays.asList(
            "C:\\cygwin64\\bin\\bash.exe",
            "C:\\cygwin\\bin\\bash.exe",
            "C:\\cygwin32\\bin\\bash.exe",
            System.getProperty("user.home", "") + "\\cygwin64\\bin\\bash.exe",
            System.getProperty("user.home", "") + "\\scoop\\apps\\cygwin\\current\\bin\\bash.exe"
        );
        
        for (String path : cygwinPaths) {
            Path bashPath = Paths.get(path);
            if (Files.exists(bashPath)) {
                logger.info("Found shell in Cygwin path: {}", path);
                return Optional.of(new ShellInfo("cygwin", path));
            }
        }
        
        // Git Bash 常见安装路径（除了 PATH 之外）
        List<String> gitBashPaths = Arrays.asList(
            "C:\\Program Files\\Git\\bin\\bash.exe",
            "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
            System.getProperty("user.home", "") + "\\AppData\\Local\\Programs\\Git\\bin\\bash.exe",
            System.getProperty("user.home", "") + "\\scoop\\apps\\git\\current\\bin\\bash.exe"
        );
        
        for (String path : gitBashPaths) {
            Path bashPath = Paths.get(path);
            if (Files.exists(bashPath)) {
                logger.info("Found shell in Git Bash path: {}", path);
                return Optional.of(new ShellInfo("git_bash", path));
            }
        }
        
        // 在 Program Files 下扫描 MinGW 和 Cygwin
        Optional<ShellInfo> scannedShell = scanForInstalledShells();
        if (scannedShell.isPresent()) {
            return scannedShell;
        }
        
        return Optional.empty();
    }
    
    /**
     * 在 Program Files 和用户目录下扫描 MinGW、Cygwin、Git Bash
     */
    private static Optional<ShellInfo> scanForInstalledShells() {
        List<String> searchRoots = Arrays.asList(
            "C:\\Program Files",
            "C:\\Program Files (x86)",
            System.getProperty("user.home", ""),
            "C:\\tools",
            "C:\\"
        );
        
        for (String root : searchRoots) {
            Path rootPath = Paths.get(root);
            if (!Files.isDirectory(rootPath)) {
                continue;
            }
            
            try (Stream<Path> stream = Files.walk(rootPath, 5)) {
                Optional<Path> found = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        String parent = p.getParent() != null ? p.getParent().toString().toLowerCase() : "";
                        
                        // 查找 bash.exe
                        if (!"bash.exe".equals(name)) {
                            return false;
                        }
                        
                        // 识别类型
                        if (parent.contains("git") && parent.contains("bin")) {
                            return true;
                        }
                        if (parent.contains("mingw") || parent.contains("msys")) {
                            return true;
                        }
                        if (parent.contains("cygwin")) {
                            return true;
                        }
                        
                        return false;
                    })
                    .findFirst();
                    
                if (found.isPresent()) {
                    String pathStr = found.get().toString();
                    String type = identifyBashType(pathStr.toLowerCase());
                    logger.info("Found shell by scanning: {} (type: {})", pathStr, type);
                    return Optional.of(new ShellInfo(type, pathStr));
                }
            } catch (Exception e) {
                logger.debug("Error scanning {}: {}", root, e.getMessage());
            }
        }
        
        return Optional.empty();
    }

    /**
     * 查找可执行文件的完整路径（不提取根目录）
     * @param executableName 可执行文件名（不带扩展名）
     * @return 可执行文件的完整路径
     */
    public static Optional<Path> findExecutablePath(String executableName) {
        String exeName = IS_WINDOWS ? executableName + ".exe" : executableName;
        
        // 第一步：通过 which/where 命令查找
        try {
            String command = IS_WINDOWS ? "where" : "which";
            ProcessBuilder pb = new ProcessBuilder(command, executableName);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String firstLine = reader.readLine();
                    if (firstLine != null && !firstLine.trim().isEmpty()) {
                        Path execPath = Paths.get(firstLine.trim());
                        if (Files.exists(execPath)) {
                            return Optional.of(execPath);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to find {} via which/where: {}", executableName, e.getMessage());
        }
        
        // 第二步：在 PATH 环境变量中查找
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] paths = pathEnv.split(IS_WINDOWS ? ";" : ":");
            for (String p : paths) {
                Path fullPath = Paths.get(p, exeName);
                if (Files.exists(fullPath)) {
                    return Optional.of(fullPath);
                }
            }
        }

        // 第三步：在默认安装目录中扫描
        List<String> searchRoots = IS_WINDOWS ? WINDOWS_SEARCH_ROOTS : UNIX_SEARCH_ROOTS;
        for (String root : searchRoots) {
            Path rootPath = Paths.get(root);
            if (!Files.isDirectory(rootPath)) {
                continue;
            }

            try (Stream<Path> stream = Files.walk(rootPath, SEARCH_DEPTH)) {
                Optional<Path> found = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(exeName))
                    .findFirst();

                if (found.isPresent()) {
                    return found;
                }
            } catch (Exception e) {
                logger.debug("Error scanning {}: {}", root, e.getMessage());
            }
        }
        
        return Optional.empty();
    }

    /**
     * 识别 bash 的类型
     */
    private static String identifyBashType(String path) {
        if (path.contains("git") && path.contains("bash")) {
            return "git_bash";
        }
        if (path.contains("mingw64") || path.contains("msys64")) {
            return "mingw64";
        }
        if (path.contains("cygwin")) {
            return "cygwin";
        }
        if (path.contains("wsl") || path.contains("ubuntu") || path.contains("linux")) {
            return "wsl2";
        }
        // 默认认为是 git_bash（最常见的）
        return "git_bash";
    }

    /**
     * 在 Windows 上检测 Python 是否存在
     * @return PythonInfo 或空
     */
    public static Optional<PythonInfo> findPythonOnWindows() {
        if (!IS_WINDOWS) {
            return Optional.empty();
        }

        Optional<Path> pythonPath = findInstallation("python");
        if (pythonPath.isPresent()) {
            return Optional.of(new PythonInfo(pythonPath.get().toString()));
        }

        return Optional.empty();
    }

    /**
     * 在 Windows 上检测 Node.js 是否存在
     * @return NodeInfo 或空
     */
    public static Optional<NodeInfo> findNodeOnWindows() {
        if (!IS_WINDOWS) {
            return Optional.empty();
        }

        Optional<Path> nodePath = findInstallation("node");
        if (nodePath.isPresent()) {
            return Optional.of(new NodeInfo(nodePath.get().toString()));
        }

        return Optional.empty();
    }

    /**
     * 在 Windows 上检测 Maven 是否存在
     * @return MavenInfo 或空
     */
    public static Optional<MavenInfo> findMavenOnWindows() {
        if (!IS_WINDOWS) {
            return Optional.empty();
        }

        // 先检查 MAVEN_HOME 环境变量
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome != null && !mavenHome.isEmpty()) {
            Path homePath = Paths.get(mavenHome);
            Path mvnPath = homePath.resolve("bin").resolve("mvn.cmd");
            if (Files.exists(mvnPath)) {
                return Optional.of(new MavenInfo(mavenHome, mvnPath.toString()));
            }
        }

        // 检查 M2_HOME 环境变量（旧版本）
        String m2Home = System.getenv("M2_HOME");
        if (m2Home != null && !m2Home.isEmpty()) {
            Path homePath = Paths.get(m2Home);
            Path mvnPath = homePath.resolve("bin").resolve("mvn.cmd");
            if (Files.exists(mvnPath)) {
                return Optional.of(new MavenInfo(m2Home, mvnPath.toString()));
            }
        }

        // 通过 PATH 查找
        Optional<Path> mvnPath = findInstallation("mvn");
        if (mvnPath.isPresent()) {
            return Optional.of(new MavenInfo(mvnPath.get().toString(), 
                mvnPath.get().resolve("bin").resolve("mvn.cmd").toString()));
        }

        return Optional.empty();
    }

    /**
     * 在 Windows 上检测 Git 是否存在
     * @return GitInfo 或空
     */
    public static Optional<GitInfo> findGitOnWindows() {
        if (!IS_WINDOWS) {
            return Optional.empty();
        }

        // 先检查 GIT_HOME 环境变量
        String gitHome = System.getenv("GIT_HOME");
        if (gitHome != null && !gitHome.isEmpty()) {
            Path homePath = Paths.get(gitHome);
            Path gitPath = homePath.resolve("bin").resolve("git.exe");
            if (Files.exists(gitPath)) {
                return Optional.of(new GitInfo(gitHome, gitPath.toString()));
            }
        }

        // 通过 PATH 查找 git.exe
        Optional<Path> gitExecPath = findExecutablePath("git");
        if (gitExecPath.isPresent()) {
            Path execPath = gitExecPath.get();
            // 提取 Git 安装目录
            Path parent = execPath.getParent();
            if (parent != null && "bin".equals(parent.getFileName().toString())) {
                Path gitHomePath = parent.getParent();
                if (gitHomePath != null) {
                    return Optional.of(new GitInfo(gitHomePath.toString(), execPath.toString()));
                }
            }
            return Optional.of(new GitInfo(parent.toString(), execPath.toString()));
        }

        // 常见 Git 安装路径
        List<String> commonPaths = Arrays.asList(
            "C:\\Program Files\\Git\\bin\\git.exe",
            "C:\\Program Files (x86)\\Git\\bin\\git.exe",
            System.getProperty("user.home", "") + "\\AppData\\Local\\Programs\\Git\\bin\\git.exe",
            System.getProperty("user.home", "") + "\\scoop\\apps\\git\\current\\bin\\git.exe"
        );

        for (String path : commonPaths) {
            Path gitPath = Paths.get(path);
            if (Files.exists(gitPath)) {
                Path gitHomePath = gitPath.getParent().getParent();
                return Optional.of(new GitInfo(
                    gitHomePath != null ? gitHomePath.toString() : gitPath.getParent().toString(),
                    gitPath.toString()
                ));
            }
        }

        return Optional.empty();
    }

    /**
     * 检测 WSL 是否可用
     */
    private static Optional<ShellInfo> detectWSL() {
        try {
            ProcessBuilder pb = new ProcessBuilder("wsl.exe", "--list", "--verbose");
            pb.redirectErrorStream(true);
            Process p = pb.start();

            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                return Optional.empty();
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String lower = line.toLowerCase();
                    // 检测是否有 Linux 发行版
                    if (lower.contains("ubuntu") ||
                        lower.contains("debian") ||
                        lower.contains("kali") ||
                        lower.contains("wsl")) {
                        // WSL 存在，返回 wsl.exe 路径
                        return Optional.of(new ShellInfo("wsl2", "wsl.exe"));
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("WSL detection failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public static class ShellInfo {
        private final String type;
        private final String path;

        public ShellInfo(String type, String path) {
            this.type = type;
            this.path = path;
        }

        public String getType() { return type; }
        public String getPath() { return path; }

        @Override
        public String toString() {
            return type + ":" + path;
        }
    }

    public static class PythonInfo {
        private final String path;

        public PythonInfo(String path) {
            this.path = path;
        }

        public String getPath() { return path; }

        @Override
        public String toString() {
            return path;
        }
    }

    public static class NodeInfo {
        private final String path;

        public NodeInfo(String path) {
            this.path = path;
        }

        public String getPath() { return path; }

        @Override
        public String toString() {
            return path;
        }
    }

    public static class MavenInfo {
        private final String homePath;
        private final String executablePath;

        public MavenInfo(String homePath, String executablePath) {
            this.homePath = homePath;
            this.executablePath = executablePath;
        }

        public String getHomePath() { return homePath; }
        public String getExecutablePath() { return executablePath; }

        @Override
        public String toString() {
            return "Maven[" + homePath + "]";
        }
    }

    public static class GitInfo {
        private final String homePath;
        private final String executablePath;

        public GitInfo(String homePath, String executablePath) {
            this.homePath = homePath;
            this.executablePath = executablePath;
        }

        public String getHomePath() { return homePath; }
        public String getExecutablePath() { return executablePath; }

        @Override
        public String toString() {
            return "Git[" + homePath + "]";
        }
    }
}
