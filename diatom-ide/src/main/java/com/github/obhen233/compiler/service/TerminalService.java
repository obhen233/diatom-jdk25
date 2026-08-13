package com.github.obhen233.compiler.service;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.i18n.I18n;
import com.github.obhen233.core.tool.builtin.CommandValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service for terminal shell command execution, path resolution, and command validation.
 * Extracted from TerminalWebSocketHandler to reduce handler complexity.
 */
@Service
public class TerminalService {

    private static final Logger logger = LoggerFactory.getLogger(TerminalService.class);

    /** Per-session cwd tracking */
    private final ConcurrentHashMap<String, String> sessionCwdMap = new ConcurrentHashMap<>();

    /** Per-session project name tracking */
    private final ConcurrentHashMap<String, String> sessionProjectMap = new ConcurrentHashMap<>();

    // Command validator for terminal mode
    private static final CommandValidator terminalValidator = new CommandValidator.Builder()
            .mode("terminal")
            .whitelistMode(false)
            .loadBuiltin(true)
            .build();

    // ==================== Shell Command Execution ====================

    /**
     * Execute a shell command and return the output lines.
     * Returns exit code and output lines via the callback.
     */
    public void executeCommand(String command, String projectName, String clientCwd,
                                String sessionId, ShellOutputCallback callback) throws Exception {
        // Resolve working directory
        File cwd = resolveCwd(projectName, clientCwd, sessionId);

        // Validate path stays within workspace
        if (!isPathWithinWorkspace(cwd)) {
            callback.onError(I18n.get("terminal.accessDenied"));
            callback.onExit(-1, cwd.getAbsolutePath());
            return;
        }

        // Handle cd command locally
        if (isCdCommand(command)) {
            handleCdLocally(command, cwd, sessionId, projectName, callback);
            return;
        }

        // Validate cd command (if command contains cd-like path traversal)
        String cdCheck = validateCdCommand(command, cwd);
        if (cdCheck != null) {
            callback.onError(cdCheck);
            callback.onExit(-1, cwd.getAbsolutePath());
            return;
        }

        // Build process
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();
        if (isWin) {
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add("chcp 65001 >nul && " + command);
        } else {
            cmd.add("/bin/sh");
            cmd.add("-c");
            cmd.add(command);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");
        pb.environment().put("LANG", "en_US.UTF-8");
        if (isWin) {
            pb.environment().put("LC_ALL", "en_US.UTF-8");
            pb.environment().put("GIT_TRACE", "1");
        }

        Process process = pb.start();
        int exitCode;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8"))) {
            String line;
            long totalOutput = 0;
            while ((line = reader.readLine()) != null) {
                callback.onStdout(line + "\n");
                totalOutput += line.length() + 1;
                if (totalOutput > 512 * 1024) {
                    callback.onStdout("... (output too long, truncated)\n");
                    process.destroyForcibly();
                    break;
                }
            }
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            callback.onStdout("\n(command timeout, terminated)\n");
            exitCode = -1;
        } else {
            exitCode = process.exitValue();
        }

        // Track cd locally
        if (exitCode == 0 && isCdCommand(command)) {
            File newCwd = resolveCdTarget(command, cwd);
            if (newCwd != null) {
                sessionCwdMap.put(sessionId, newCwd.getAbsolutePath());
                cwd = newCwd;
            }
        }

        callback.onExit(exitCode, cwd.getAbsolutePath());
    }

    /**
     * Validate command before execution.
     */
    public String validateCommand(String command) {
        CommandValidator.ValidationResult result = terminalValidator.validate(command);
        if (result.isDenied()) {
            return I18n.get("terminal.cmdDangerous", command) + " (" + result.getReason() + ")";
        }
        return null;
    }

    /**
     * Validate cd command target path.
     */
    public String validateCdCommand(String command, File cwd) {
        String lower = command.trim().toLowerCase();

        if (!lower.startsWith("cd ") && !lower.startsWith("cd\t")) {
            return null;
        }

        String target;
        if (lower.startsWith("cd ")) {
            target = command.substring(3).trim();
        } else {
            target = command.substring(2).trim();
        }

        if ((target.startsWith("\"") && target.endsWith("\"")) ||
                (target.startsWith("'") && target.endsWith("'"))) {
            target = target.substring(1, target.length() - 1);
        }

        if (target.isEmpty()) {
            return null;
        }

        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");

        if (isWin) {
            if (target.matches("^[A-Za-z]:\\\\.*") || target.startsWith("/") || target.startsWith("\\")) {
                return I18n.get("terminal.cdAbsolutePath");
            }
        } else {
            if (target.startsWith("/")) {
                return I18n.get("terminal.cdAbsolutePath");
            }
        }

        return null;
    }

    // ==================== cd Command Handling ====================

    public boolean isCdCommand(String command) {
        String lower = command.trim().toLowerCase();
        return lower.startsWith("cd ") || lower.startsWith("cd\t") || lower.equals("cd");
    }

    public File resolveCdTarget(String command, File currentCwd) {
        String lower = command.trim().toLowerCase();
        String target;
        if (lower.equals("cd")) {
            return currentCwd;
        }
        if (lower.startsWith("cd ")) {
            target = command.substring(3).trim();
        } else {
            target = command.substring(2).trim();
        }
        if ((target.startsWith("\"") && target.endsWith("\"")) ||
                (target.startsWith("'") && target.endsWith("'"))) {
            target = target.substring(1, target.length() - 1);
        }
        if (target.isEmpty()) {
            return currentCwd;
        }
        // Normalize target
        target = target.replace('\\', '/').replaceAll("/+", "/");
        try {
            File targetDir = new File(currentCwd, target).getCanonicalFile();
            if (targetDir.exists() && targetDir.isDirectory()) {
                return targetDir;
            }
        } catch (Exception ignored) {}
        return null;
    }

    public void handleCdLocally(String command, File currentCwd, String sessionId,
                                 String projectName, ShellOutputCallback callback) throws Exception {
        File newCwd = resolveCdTarget(command, currentCwd);
        if (newCwd != null) {
            if (!isPathWithinWorkspace(newCwd)) {
                callback.onError(I18n.get("terminal.cdOutOfWorkspace"));
                callback.onExit(1, currentCwd.getAbsolutePath());
                return;
            }
            sessionCwdMap.put(sessionId, newCwd.getAbsolutePath());
            callback.onExit(0, newCwd.getAbsolutePath());
        } else {
            callback.onStdout("cd: no such directory\n");
            callback.onExit(1, currentCwd.getAbsolutePath());
        }
    }

    // ==================== Path Resolution ====================

    public File resolveCwd(String projectName, String clientCwd, String sessionId) {
        String resolvedCwd = null;
        if (clientCwd != null && !clientCwd.isEmpty()) {
            resolvedCwd = clientCwd;
        } else if (sessionCwdMap.containsKey(sessionId)) {
            resolvedCwd = sessionCwdMap.get(sessionId);
        }
        if (resolvedCwd != null) {
            File candidate = new File(resolvedCwd);
            if (candidate.exists() && candidate.isDirectory()) {
                return candidate;
            }
        }
        if (projectName != null && !projectName.isEmpty()) {
            File projectDir = new File(Constants.workspacePath, projectName);
            if (projectDir.exists()) return projectDir;
        }
        return new File(Constants.workspacePath);
    }

    public String getCwd(String sessionId, String projectName) {
        String cwd = sessionCwdMap.get(sessionId);
        if (cwd != null) return cwd;
        if (projectName != null && !projectName.isEmpty()) {
            return new File(Constants.workspacePath, projectName).getAbsolutePath();
        }
        return Constants.workspacePath;
    }

    public boolean isPathWithinWorkspace(File path) {
        try {
            String canonical = path.getCanonicalPath();
            String base = new File(Constants.workspacePath).getCanonicalPath();
            return canonical.startsWith(base);
        } catch (IOException e) {
            return false;
        }
    }

    // ==================== Tab Completion ====================

    /**
     * Get tab completion candidates for a file path prefix.
     */
    public CompleterResult getCompletionCandidates(String prefix, String projectName,
                                                    String clientCwd, String sessionId) {
        if (prefix == null) prefix = "";

        File cwd = resolveCwd(projectName, clientCwd, sessionId);
        if (!isPathWithinWorkspace(cwd)) {
            return new CompleterResult(Collections.<String>emptyList());
        }

        String normalizedPrefix = prefix.replace('\\', '/').replaceAll("/+", "/");
        final int slashPos = normalizedPrefix.lastIndexOf('/');
        final String partial;
        final File listDir;

        if (slashPos >= 0) {
            String dirPart = normalizedPrefix.substring(0, slashPos);
            partial = prefix.substring(slashPos + 1);
            if (!dirPart.isEmpty()) {
                File subDir = new File(cwd, dirPart);
                if (subDir.exists() && subDir.isDirectory()) {
                    listDir = subDir;
                } else {
                    return new CompleterResult(Collections.<String>emptyList());
                }
            } else {
                listDir = cwd;
            }
        } else {
            partial = prefix;
            listDir = cwd;
        }

        final String matchPrefix = partial;
        File[] files = listDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return !f.isHidden() && f.getName().startsWith(matchPrefix)
                    && (f.isDirectory() || f.isFile());
            }
        });

        List<String> candidates = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : files) {
                String name = f.getName();
                if (f.isDirectory()) name += "/";
                if (slashPos >= 0) {
                    candidates.add(prefix.substring(0, slashPos + 1) + name);
                } else {
                    candidates.add(name);
                }
            }
        }

        return new CompleterResult(candidates);
    }

    /**
     * Update session cwd tracking.
     */
    public void updateSessionCwd(String sessionId, String cwd) {
        if (cwd != null) {
            sessionCwdMap.put(sessionId, cwd);
        }
    }

    /**
     * Update session project tracking.
     */
    public void updateSessionProject(String sessionId, String projectName) {
        if (projectName != null && !projectName.isEmpty()) {
            sessionProjectMap.put(sessionId, projectName);
        }
    }

    /**
     * Remove session tracking data.
     */
    public void removeSession(String sessionId) {
        sessionCwdMap.remove(sessionId);
        sessionProjectMap.remove(sessionId);
    }

    // ==================== Callback Interfaces ====================

    public interface ShellOutputCallback {
        void onStdout(String data);
        void onExit(int code, String cwd);
        void onError(String message);
    }

    // ==================== Data Classes ====================

    public static class CompleterResult {
        private final List<String> candidates;

        public CompleterResult(List<String> candidates) {
            this.candidates = candidates;
        }

        public List<String> getCandidates() {
            return candidates;
        }

        public boolean isEmpty() {
            return candidates.isEmpty();
        }
    }
}
