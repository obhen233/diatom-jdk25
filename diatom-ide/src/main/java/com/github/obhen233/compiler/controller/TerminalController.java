package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.workspace.TerminalCommandRequest;
import com.github.obhen233.compiler.i18n.I18n;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Terminal command execution controller.
 * Provides shell command execution within the workspace context.
 */
@CrossOrigin
@RestController
@RequestMapping("/workspace")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Terminal / 终端", description = "Terminal command execution / 终端命令执行")
public class TerminalController {

    private final ConcurrentHashMap<String, String> projectCwdMap = new ConcurrentHashMap<>();

    private static final String[] ALLOWED_COMMANDS = {
        "ls", "dir", "cat", "type", "echo", "pwd", "cd",
        "java", "javac", "javap", "jar",
        "mvn", "gradle", "gradlew",
        "git", "svn",
        "node", "npm", "npx", "yarn",
        "python", "pip",
        "curl", "wget",
        "find", "grep", "head", "tail", "wc", "sort", "uniq",
        "mkdir", "touch", "cp", "mv", "rm",
        "tree", "env", "set", "where", "which",
        "docker", "docker-compose",
        "ping", "ipconfig", "ifconfig", "netstat",
        "clear", "cls"
    };

    private static final String[] BLOCKED_PATTERNS = {
        "format ", "fdisk", "mkfs", "dd if=",
        "shutdown", "reboot", "halt", "poweroff",
        "rm -rf /", "rm -rf /*", "del /s /q c:\\",
        "> /dev/", "| bash", "| sh", "| cmd",
        "eval ", "exec ", "`", "$(",
        "passwd", "useradd", "userdel", "visudo",
        "chmod 777 /", "chown -R",
        "registry", "reg add", "reg delete",
        "net user", "net localgroup",
        "taskkill /f /im"
    };

    @PostMapping("/terminal")
    @Operation(summary = "Execute terminal command / 执行终端命令", description = "Executes a shell command in the workspace or project context. " +
            "Supports cd, running Java commands, Maven/Gradle, etc. / 在工作空间或项目上下文中执行shell命令。支持cd、运行Java命令、Maven/Gradle等。")
    public ApiResponse<Map<String, Object>> executeTerminalCommand(@RequestBody TerminalCommandRequest body) {
        String command = body.command();
        String projectName = body.projectName();
        String clientCwd = body.cwd();
        if (command == null || command.trim().isEmpty()) return ApiResponse.fail(I18n.get("terminal.cmdEmpty"));
        command = command.trim();

        String checkResult = validateCommand(command);
        if (checkResult != null) return ApiResponse.fail(I18n.get(checkResult, command));

        String projectKey = (projectName != null && !projectName.isEmpty()) ? projectName : "__workspace__";

        File cwd = null;
        String resolvedCwd = null;
        if (clientCwd != null && !clientCwd.isEmpty()) {
            resolvedCwd = clientCwd;
        } else if (projectCwdMap.containsKey(projectKey)) {
            resolvedCwd = projectCwdMap.get(projectKey);
        }
        if (resolvedCwd != null) {
            File candidateCwd = new File(resolvedCwd);
            if (candidateCwd.exists() && candidateCwd.isDirectory()) {
                cwd = candidateCwd;
            }
        }
        if (cwd == null) {
            if (projectName != null && !projectName.isEmpty()) {
                cwd = new File(Constants.workspacePath, projectName);
                if (!cwd.exists()) cwd = new File(Constants.workspacePath);
            } else {
                cwd = new File(Constants.workspacePath);
            }
        }

        try {
            if (!cwd.getCanonicalPath().startsWith(new File(Constants.workspacePath).getCanonicalPath())) {
                return ApiResponse.fail(I18n.get("terminal.accessDenied"));
            }
        } catch (Exception e) {
            return ApiResponse.fail(I18n.get("terminal.pathCheckFailed"));
        }

        checkResult = validateCdCommand(command, cwd);
        if (checkResult != null) return ApiResponse.fail(I18n.get(checkResult));

        try {
            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
            List<String> cmd = new ArrayList<>();
            if (isWin) {
                cmd.add("cmd.exe");
                cmd.add("/c");
                // chcp 65001 切换到 UTF-8 代码页，解决 Windows 中文乱码问题
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
                // Windows 下 LC_ALL 可确保 msys/cygwin 工具也输出 UTF-8
                pb.environment().put("LC_ALL", "en_US.UTF-8");
                pb.environment().put("GIT_TRACE", "1");
            }

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (output.length() > 512 * 1024) {
                        output.append("\n... (output too long, truncated)\n");
                        break;
                    }
                }
            }

            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                output.append("\n(command timeout, terminated)\n");
            }

            int exitCode = finished ? process.exitValue() : -1;

            if (exitCode == 0 && isCdCommand(command)) {
                File newCwd = resolveCdTarget(command, cwd);
                if (newCwd != null) {
                    projectCwdMap.put(projectKey, newCwd.getAbsolutePath());
                    cwd = newCwd;
                }
            }

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("output", output.toString());
            resultData.put("exitCode", exitCode);
            resultData.put("cwd", cwd.getAbsolutePath());
            return ApiResponse.ok(resultData);
        } catch (Exception e) {
            return ApiResponse.fail(I18n.get("terminal.execFailed", e.getMessage()));
        }
    }

    private boolean isCdCommand(String command) {
        String lower = command.trim().toLowerCase();
        return lower.startsWith("cd ") || lower.startsWith("cd\t") || lower.equals("cd");
    }

    private File resolveCdTarget(String command, File currentCwd) {
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
        try {
            File targetDir = new File(currentCwd, target).getCanonicalFile();
            if (targetDir.exists() && targetDir.isDirectory()) {
                return targetDir;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String validateCommand(String command) {
        String lower = command.toLowerCase().trim();

        for (String blocked : BLOCKED_PATTERNS) {
            if (lower.contains(blocked.toLowerCase())) {
                return "terminal.cmdDangerous";
            }
        }

        String firstWord = lower.split("[\\s|&;]+")[0];
        if (firstWord.contains("/")) firstWord = firstWord.substring(firstWord.lastIndexOf('/') + 1);
        if (firstWord.contains("\\")) firstWord = firstWord.substring(firstWord.lastIndexOf('\\') + 1);
        if (firstWord.endsWith(".exe")) firstWord = firstWord.substring(0, firstWord.length() - 4);
        if (firstWord.endsWith(".cmd")) firstWord = firstWord.substring(0, firstWord.length() - 4);
        if (firstWord.endsWith(".bat")) firstWord = firstWord.substring(0, firstWord.length() - 4);

        boolean allowed = false;
        for (String a : ALLOWED_COMMANDS) {
            if (firstWord.equals(a) || firstWord.equals(a + "w")) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            return "terminal.cmdNotAllowed";
        }

        // 验证命令参数，防止通过参数注入危险命令
        String argsValidation = validateCommandArgs(command);
        if (argsValidation != null) {
            return argsValidation;
        }

        return null;
    }

    /**
     * 验证命令参数。
     * 检查参数中是否包含危险模式，防止通过参数注入。
     */
    private String validateCommandArgs(String command) {
        // 危险模式：shell 元字符和路径遍历模式
        String[] dangerousPatterns = {
            "&&", "||", ";", "|", ">>", "2>", "2>&1",
            "$(", "`", "${",
            "../", "..\\", "%00", "\0",
            "curl |", "wget |", "bash -c", "sh -c",
            "nc -e", "/dev/tcp", "/dev/null",
            "0<&-", "1>&-", "2>&-"
        };

        String lower = command.toLowerCase();
        for (String pattern : dangerousPatterns) {
            if (lower.contains(pattern.toLowerCase())) {
                return "terminal.cmdDangerous";
            }
        }

        // 特殊检查：防止通过 -e 或 -c 参数执行危险命令
        String[] parts = command.split("\\s+");
        for (int i = 1; i < parts.length; i++) {
            String arg = parts[i];
            // 检查是否以 |, &, ;, $ 等危险字符开头
            if (arg.matches("^[|&;$`].*")) {
                return "terminal.cmdDangerous";
            }
            // 检查路径参数是否包含危险模式
            if (arg.contains("..") || arg.contains("&&") || arg.contains("||")) {
                return "terminal.cmdDangerous";
            }
        }

        return null;
    }

    private String validateCdCommand(String command, File cwd) {
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
                return "terminal.cdAbsolutePath";
            }
        } else {
            if (target.startsWith("/")) {
                return "terminal.cdAbsolutePath";
            }
        }

        File targetDir;
        try {
            targetDir = new File(cwd, target).getCanonicalFile();
        } catch (Exception e) {
            return "terminal.pathCheckFailed";
        }

        String allowedBase = null;
        String targetPath = null;
        try {
            allowedBase = new File(Constants.workspacePath).getCanonicalPath();
            targetPath = targetDir.getCanonicalPath();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (!targetPath.startsWith(allowedBase)) {
            return "terminal.cdOutOfWorkspace";
        }

        return null;
    }
}
