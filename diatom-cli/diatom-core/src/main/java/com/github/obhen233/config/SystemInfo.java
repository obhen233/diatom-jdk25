package com.github.obhen233.config;

import com.github.obhen233.util.SoftwareLocator;
import com.github.obhen233.util.SoftwareLocator.ShellInfo;
import com.github.obhen233.util.SoftwareLocator.PythonInfo;
import com.github.obhen233.util.SoftwareLocator.NodeInfo;
import com.github.obhen233.util.SoftwareLocator.MavenInfo;
import com.github.obhen233.util.SoftwareLocator.GitInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 系统环境信息收集器
 */
public class SystemInfo {
    private static final Logger logger = LoggerFactory.getLogger(SystemInfo.class);
    private static final String NEWLINE = System.lineSeparator();

    private final String osName;
    private final String osVersion;
    private final String osArch;
    private final String javaVersion;
    private final long totalMemory;
    private final long maxMemory;
    private final int availableProcessors;
    private final String userHome;
    private final String userDir;
    private final String javaHome;
    private final String detectedShell;
    private final String shellType;
    private final String detectedPython;
    private final String pythonHome;
    private final String detectedNode;
    private final String nodeHome;
    private final String detectedMaven;
    private final String mavenHome;
    private final String detectedGit;
    private final String gitHome;

    public SystemInfo() {
        this.osName = System.getProperty("os.name", "unknown");
        this.osVersion = System.getProperty("os.version", "unknown");
        this.osArch = System.getProperty("os.arch", "unknown");
        this.javaVersion = System.getProperty("java.version", "unknown");
        this.totalMemory = Runtime.getRuntime().totalMemory();
        this.maxMemory = Runtime.getRuntime().maxMemory();
        this.availableProcessors = Runtime.getRuntime().availableProcessors();
        this.userHome = System.getProperty("user.home", "unknown");
        this.userDir = System.getProperty("user.dir", "unknown");
        this.javaHome = System.getProperty("java.home", "unknown");

        // Detect Unix-like shell on Windows using SoftwareLocator
        Optional<SoftwareLocator.ShellInfo> shellInfo = SoftwareLocator.findBashOnWindows();
        if (shellInfo.isPresent()) {
            this.detectedShell = shellInfo.get().getPath();
            this.shellType = shellInfo.get().getType();
        } else {
            this.detectedShell = null;
            this.shellType = isWindows() ? "cmd" : "native";
        }

        // Detect Python on Windows
        Optional<PythonInfo> pythonInfo = SoftwareLocator.findPythonOnWindows();
        if (pythonInfo.isPresent()) {
            this.detectedPython = pythonInfo.get().getPath();
            this.pythonHome = pythonInfo.get().getPath();
        } else {
            this.detectedPython = null;
            this.pythonHome = null;
        }

        // Detect Node.js on Windows
        Optional<NodeInfo> nodeInfo = SoftwareLocator.findNodeOnWindows();
        if (nodeInfo.isPresent()) {
            this.detectedNode = nodeInfo.get().getPath();
            this.nodeHome = nodeInfo.get().getPath();
        } else {
            this.detectedNode = null;
            this.nodeHome = null;
        }

        // Detect Maven on Windows
        Optional<MavenInfo> mavenInfo = SoftwareLocator.findMavenOnWindows();
        if (mavenInfo.isPresent()) {
            this.detectedMaven = mavenInfo.get().getExecutablePath();
            this.mavenHome = mavenInfo.get().getHomePath();
        } else {
            this.detectedMaven = null;
            this.mavenHome = null;
        }

        // Detect Git on Windows
        Optional<GitInfo> gitInfo = SoftwareLocator.findGitOnWindows();
        if (gitInfo.isPresent()) {
            this.detectedGit = gitInfo.get().getExecutablePath();
            this.gitHome = gitInfo.get().getHomePath();
        } else {
            this.detectedGit = null;
            this.gitHome = null;
        }

        logger.info("SystemInfo initialized: OS={} {}, Arch={}, CPUs={}, Shell={}, Python={}, Node={}, Maven={}, Git={}",
                    osName, osVersion, osArch, availableProcessors, shellType,
                    pythonHome != null ? pythonHome : "not found",
                    nodeHome != null ? nodeHome : "not found",
                    mavenHome != null ? mavenHome : "not found",
                    gitHome != null ? gitHome : "not found");
    }

    /**
     * 构建系统信息摘要，用于传递给 AI
     * 精简格式：只保留 AI 做决策需要的关键信息
     */
    public String buildSummary() {
        StringBuilder sb = new StringBuilder();
        
        // Compact format: OS, Dir, Maven, Shell on one line
        // Example: "OS: Windows(amd64) | Dir: D:\diatom | Maven: D:\apache-maven-3.8.5 | Shell: git_bash"
        sb.append("OS: ").append(getOsType()).append("(").append(osArch).append(")");
        sb.append(" | Dir: ").append(userDir);
        
        boolean hasTools = false;
        if (detectedMaven != null) {
            sb.append(" | Maven: ").append(mavenHome != null ? mavenHome : detectedMaven);
            hasTools = true;
        }
        if (detectedPython != null) {
            sb.append(" | Python: ").append(pythonHome != null ? pythonHome : detectedPython);
            hasTools = true;
        }
        if (detectedNode != null) {
            sb.append(" | Node: ").append(nodeHome != null ? nodeHome : detectedNode);
            hasTools = true;
        }
        if (detectedGit != null && !"cmd".equals(shellType)) {
            sb.append(" | Shell: ").append(shellType);
            hasTools = true;
        }
        
        sb.append(NEWLINE);
        return sb.toString();
    }

    /**
     * 检查是否为 Windows 系统
     */
    public boolean isWindows() {
        return osName.toLowerCase().contains("windows");
    }

    /**
     * 检查是否为 Linux 系统
     */
    public boolean isLinux() {
        return osName.toLowerCase().contains("linux");
    }

    /**
     * 检查是否为 macOS 系统
     */
    public boolean isMac() {
        return osName.toLowerCase().contains("mac");
    }

    /**
     * 获取操作系统类型简称
     */
    public String getOsType() {
        if (isWindows()) return "Windows";
        if (isLinux()) return "Linux";
        if (isMac()) return "macOS";
        return osName;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int unit = 0;
        double value = bytes;
        while (value >= 1024 && unit < 3) {
            value /= 1024;
            unit++;
        }
        String[] units = {"B", "KB", "MB", "GB"};
        return String.format("%.1f %s", value, units[unit]);
    }

    // Getters
    public String getOsName() { return osName; }
    public String getOsVersion() { return osVersion; }
    public String getOsArch() { return osArch; }
    public String getJavaVersion() { return javaVersion; }
    public long getTotalMemory() { return totalMemory; }
    public long getMaxMemory() { return maxMemory; }
    public int getAvailableProcessors() { return availableProcessors; }
    public String getUserHome() { return userHome; }
    public String getUserDir() { return userDir; }
    public String getJavaHome() { return javaHome; }
    public String getDetectedShell() { return detectedShell; }
    public String getShellType() { return shellType; }
    public String getDetectedPython() { return detectedPython; }
    public String getPythonHome() { return pythonHome; }
    public String getDetectedNode() { return detectedNode; }
    public String getNodeHome() { return nodeHome; }
    public String getDetectedMaven() { return detectedMaven; }
    public String getMavenHome() { return mavenHome; }
    public String getDetectedGit() { return detectedGit; }
    public String getGitHome() { return gitHome; }
    public String getDetectedGitPath() { return detectedGit; }
}
