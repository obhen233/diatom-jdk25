package com.github.obhen233.core.tool.builtin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.obhen233.util.InstallPaths;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shared process environment configuration for all subprocess execution.
 * Ensures consistent PATH and environment setup across CommandTools, SelfUpdateTools, etc.
 */
public class ProcessEnvironment {
    private static final Logger logger = LoggerFactory.getLogger(ProcessEnvironment.class);
    
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final String PATH_SEP = IS_WINDOWS ? ";" : ":";
    
    // Cached environment
    private static Map<String, String> cachedEnvironment = null;
    private static String cachedPath = null;
    
    // Configurable paths (can be set by CommandTools or other components)
    private static String mavenPath = null;
    private static String pythonPath = null;
    private static String nodePath = null;
    private static String gitPath = null;
    private static String customPath = null;
    
    /**
     * Set Maven path for PATH configuration.
     */
    public static void setMavenPath(String path) {
        mavenPath = path;
        invalidateCache();
    }
    
    /**
     * Set Python path for PATH configuration.
     */
    public static void setPythonPath(String path) {
        pythonPath = path;
        invalidateCache();
    }

    /**
     * Set Node.js path for PATH configuration.
     */
    public static void setNodePath(String path) {
        nodePath = path;
        invalidateCache();
    }
    
    /**
     * Set Git path for PATH configuration.
     */
    public static void setGitPath(String path) {
        gitPath = path;
        invalidateCache();
    }
    
    /**
     * Set custom PATH additions.
     */
    public static void setCustomPath(String path) {
        customPath = path;
        invalidateCache();
    }
    
    /**
     * Invalidate cached environment.
     */
    private static void invalidateCache() {
        cachedEnvironment = null;
        cachedPath = null;
    }
    
    /**
     * Get a safe PATH string that includes essential directories.
     * @return A properly formatted PATH string
     */
    public static String getSafePath() {
        if (cachedPath != null) {
            return cachedPath;
        }
        
        StringBuilder safePath = new StringBuilder();
        Set<String> addedPaths = new HashSet<>();
        
        Consumer<String> addPath = path -> {
            if (path != null && !path.isEmpty()) {
                String normalized = IS_WINDOWS 
                    ? path.replace('/', '\\').replaceAll("\\\\+$", "") 
                    : path.replace('\\', '/').replaceAll("/+$", "");
                if (!addedPaths.contains(normalized)) {
                    if (safePath.length() > 0) {
                        safePath.append(PATH_SEP);
                    }
                    safePath.append(normalized);
                    addedPaths.add(normalized);
                }
            }
        };
        
        // Add Java home
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            addPath.accept(javaHome + (IS_WINDOWS ? "\\bin" : "/bin"));
            
            File javaHomeDir = new File(javaHome);
            File parentDir = javaHomeDir.getParentFile();
            if (parentDir != null && IS_WINDOWS) {
                File parentBin = new File(parentDir, "bin");
                if (parentBin.exists() && new File(parentBin, "javac.exe").exists()) {
                    addPath.accept(parentBin.getAbsolutePath());
                }
            }
        }
        
        // Add configured tool paths
        if (mavenPath != null) {
            File f = new File(mavenPath);
            addPath.accept(f.isDirectory() ? mavenPath : f.getParent());
        }
        if (pythonPath != null) {
            File f = new File(pythonPath);
            addPath.accept(f.isDirectory() ? pythonPath : f.getParent());
        }
        if (nodePath != null) {
            File f = new File(nodePath);
            addPath.accept(f.isDirectory() ? nodePath : f.getParent());
        }
        if (gitPath != null) {
            File f = new File(gitPath);
            addPath.accept(f.isDirectory() ? gitPath : f.getParent());
        }
        if (customPath != null) {
            addPath.accept(customPath);
        }
        
        // Add system-specific paths
        if (IS_WINDOWS) {
            addPath.accept("C:\\Windows\\System32");
            addPath.accept("C:\\Windows");
            addPath.accept("C:\\Program Files\\Git\\bin");
            addPath.accept("C:\\Program Files\\Git\\cmd");
            
            // Add common Maven locations
            addPath.accept(InstallPaths.getInstallHome().resolve("maven").resolve("bin").toString());
            addPath.accept("C:\\Program Files\\Apache\\maven\\bin");
            addPath.accept("C:\\apache-maven\\bin");
            
            // Add user's PATH if available (for tools they've installed)
            String userPath = System.getenv("PATH");
            if (userPath != null) {
                for (String p : userPath.split(PATH_SEP)) {
                    addPath.accept(p);
                }
            }
        } else {
            // Unix-like systems
            addPath.accept("/usr/local/bin");
            addPath.accept("/usr/bin");
            addPath.accept("/bin");
            addPath.accept("/usr/sbin");
            addPath.accept("/sbin");
            
            // Add Maven from common locations
            addPath.accept(InstallPaths.getInstallHome().resolve("maven").resolve("bin").toString());
            String userHome = System.getProperty("user.home");
            addPath.accept(userHome + "/.sdkman/candidates/maven/current/bin");
            addPath.accept("/usr/local/maven/bin");
            
            // Add MVN_HOME if set
            String mvnHome = System.getenv("MVN_HOME");
            if (mvnHome != null) {
                addPath.accept(mvnHome + "/bin");
            }
            String m2Home = System.getenv("M2_HOME");
            if (m2Home != null) {
                addPath.accept(m2Home + "/bin");
            }
            
            // Add user's PATH
            String userPath = System.getenv("PATH");
            if (userPath != null) {
                for (String p : userPath.split(PATH_SEP)) {
                    addPath.accept(p);
                }
            }
        }
        
        cachedPath = safePath.toString();
        logger.debug("Computed safe PATH: {}", cachedPath);
        return cachedPath;
    }
    
    /**
     * Get a clean environment map suitable for subprocess execution.
     * Includes PATH, HOME, USER, LANG, and other essential variables.
     * @return A map of environment variables
     */
    public static Map<String, String> getCleanEnvironment() {
        if (cachedEnvironment != null) {
            return new HashMap<>(cachedEnvironment);
        }
        
        Map<String, String> env = new HashMap<>();
        env.put("PATH", getSafePath());
        env.put("HOME", System.getProperty("user.home"));
        env.put("USER", System.getProperty("user.name"));
        env.put("LANG", "en_US.UTF-8");
        env.put("LC_ALL", "en_US.UTF-8");
        
        // Add JAVA_HOME
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            env.put("JAVA_HOME", javaHome);
        }

        // Add TEMP and TMP for Windows (Maven needs these for temp file creation)
        if (IS_WINDOWS) {
            String tempDir = System.getProperty("java.io.tmpdir");
            if (tempDir != null) {
                // Ensure tempDir is a proper path without trailing separator
                File tempFile = new File(tempDir);
                env.put("TEMP", tempFile.getAbsolutePath());
                env.put("TMP", tempFile.getAbsolutePath());
            }
        }

        // Preserve Maven-related environment variables if set
        String mvnHome = System.getenv("MVN_HOME");
        if (mvnHome != null) {
            env.put("MVN_HOME", mvnHome);
        }
        String m2Home = System.getenv("M2_HOME");
        if (m2Home != null) {
            env.put("M2_HOME", m2Home);
        }
        
        cachedEnvironment = env;
        return new HashMap<>(env);
    }
    
    /**
     * Configure a ProcessBuilder with the correct environment.
     * This should be called for all subprocess execution.
     * @param pb The ProcessBuilder to configure
     */
    public static void configureEnvironment(ProcessBuilder pb) {
        Map<String, String> cleanEnv = getCleanEnvironment();
        // Don't clear the entire environment — preserve system variables
        // that subprocesses may need (e.g. APPDATA, LOCALAPPDATA for Playwright
        // to find browser binaries, SYSTEMROOT for system tools, etc.)
        // Only override PATH and our whitelisted variables.
        pb.environment().putAll(cleanEnv);
    }
    
    /**
     * Create a ProcessBuilder with pre-configured environment.
     * @param command The command and arguments
     * @return A ProcessBuilder with correct environment
     */
    public static ProcessBuilder createProcessBuilder(String... command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        configureEnvironment(pb);
        return pb;
    }
}
