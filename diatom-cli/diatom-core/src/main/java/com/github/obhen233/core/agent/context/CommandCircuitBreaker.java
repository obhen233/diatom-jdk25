package com.github.obhen233.core.agent.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command execution circuit breaker for fault tolerance.
 * 
 * Features:
 * 1. Detects consecutive identical errors (e.g., CreateProcess error=5)
 * 2. Trips circuit after 3 consecutive same errors
 * 3. Provides structured error signals to LLM with recovery options
 * 4. Prevents token waste from infinite retry loops
 */
public class CommandCircuitBreaker {
    private static final Logger logger = LoggerFactory.getLogger(CommandCircuitBreaker.class);
    
    // Circuit states
    public enum CircuitState {
        CLOSED,      // Normal operation
        OPEN,        // Circuit tripped, commands blocked
        HALF_OPEN    // Testing if issue is resolved
    }
    
    // Threshold for consecutive errors before tripping
    private static final int ERROR_THRESHOLD = 3;
    
    // Time to wait before attempting reset (milliseconds)
    private static final long RESET_TIMEOUT_MS = 60_000; // 1 minute
    
    // Patterns for common critical errors
    private static final Map<Pattern, String> ERROR_PATTERNS = new LinkedHashMap<>();
    
    static {
        // Windows access denied
        ERROR_PATTERNS.put(
            Pattern.compile("CreateProcess error=(\\d+)", Pattern.CASE_INSENSITIVE),
            "PROCESS_START_FAILED"
        );
        ERROR_PATTERNS.put(
            Pattern.compile("error=5|拒绝访问|Access is denied", Pattern.CASE_INSENSITIVE),
            "ACCESS_DENIED"
        );
        // Shell path issues
        ERROR_PATTERNS.put(
            Pattern.compile("(\\S+)\\s+是目录而非可执行文件|is a directory", Pattern.CASE_INSENSITIVE),
            "SHELL_PATH_IS_DIRECTORY"
        );
        ERROR_PATTERNS.put(
            Pattern.compile("The directory name is invalid|目录名无效", Pattern.CASE_INSENSITIVE),
            "INVALID_DIRECTORY"
        );
        ERROR_PATTERNS.put(
            Pattern.compile("The system cannot find the (file|path) specified|系统找不到指定的(文件|路径)", Pattern.CASE_INSENSITIVE),
            "PATH_NOT_FOUND"
        );
        // Shell not found
        ERROR_PATTERNS.put(
            Pattern.compile("(bash|sh|cmd)\\.exe.*not found|找不到", Pattern.CASE_INSENSITIVE),
            "SHELL_NOT_FOUND"
        );
        // Permission issues
        ERROR_PATTERNS.put(
            Pattern.compile("Permission denied|权限被拒绝", Pattern.CASE_INSENSITIVE),
            "PERMISSION_DENIED"
        );
        // Generic process failure
        ERROR_PATTERNS.put(
            Pattern.compile("Failed to start process", Pattern.CASE_INSENSITIVE),
            "PROCESS_START_FAILED"
        );
    }
    
    // Error tracking: errorSignature -> (count, lastError, lastTime)
    private final Map<String, ErrorTracker> errorTrackers = new ConcurrentHashMap<>();

    // Per-error-signature circuit states (replaces global state)
    private final Map<String, CircuitState> commandCircuits = new ConcurrentHashMap<>();

    // Time when circuit was opened per error signature
    private final Map<String, Long> circuitOpenedTimes = new ConcurrentHashMap<>();

    // Current circuit state (kept for backward compatibility, tracks most recent error)
    private volatile CircuitState state = CircuitState.CLOSED;
    
    // Last error that caused the circuit to open
    private volatile ErrorInfo lastCriticalError;

    // Shell environment status
    private volatile boolean shellEnvironmentAvailable = true;
    private volatile String shellEnvironmentError = null;
    
    /**
     * Track a command execution error.
     * 
     * @param command The command that failed
     * @param errorMessage The error message
     * @return ErrorInfo with analysis and suggested action
     */
    public ErrorInfo trackError(String command, String errorMessage) {
        String errorSignature = extractErrorSignature(errorMessage);
        ErrorType errorType = classifyError(errorMessage);
        
        // Update tracker
        ErrorTracker tracker = errorTrackers.computeIfAbsent(errorSignature, k -> new ErrorTracker());
        tracker.recordError(errorMessage);
        
        logger.warn("Command error tracked: {} (type: {}, count: {})", 
            command, errorType, tracker.getCount());
        
        // Check if we should trip the circuit
        if (tracker.getCount() >= ERROR_THRESHOLD && state == CircuitState.CLOSED) {
            tripCircuit(errorSignature, errorMessage, errorType);
        }
        
        return new ErrorInfo(
            errorType,
            errorMessage,
            errorSignature,
            tracker.getCount(),
            state == CircuitState.OPEN,
            generateDiagnosis(errorMessage, errorType),
            generateRecoveryOptions(errorType)
        );
    }
    
    /**
     * Record a successful command execution (resets error counters).
     */
    public void recordSuccess() {
        // Reset all error trackers on success
        errorTrackers.clear();

        // Clear all per-signature circuit states
        commandCircuits.clear();
        circuitOpenedTimes.clear();

        // If circuit was half-open, close it
        if (state == CircuitState.HALF_OPEN || state == CircuitState.OPEN) {
            state = CircuitState.CLOSED;
            lastCriticalError = null;
            shellEnvironmentAvailable = true;
            shellEnvironmentError = null;
            logger.info("Circuit breaker reset to CLOSED state after successful execution");
        }
    }
    
    /**
     * Check if command execution should be allowed.
     * Now supports per-error-signature circuits - only blocks commands whose error
     * signature matches an open circuit, allowing unrelated commands to proceed.
     *
     * @return true if commands can be executed, false if circuit is open for a specific error
     */
    public boolean shouldAllowExecution() {
        // If no circuits are open at all, allow execution
        boolean anyOpen = commandCircuits.values().stream().anyMatch(s -> s == CircuitState.OPEN);
        if (!anyOpen) {
            // Reset global state if all circuits are closed
            if (state != CircuitState.CLOSED) {
                state = CircuitState.CLOSED;
            }
            return true;
        }

        // Some circuits are open - check if timeout has passed for any of them
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CircuitState> entry : new HashMap<>(commandCircuits).entrySet()) {
            if (entry.getValue() == CircuitState.OPEN) {
                Long openedTime = circuitOpenedTimes.get(entry.getKey());
                if (openedTime != null && now - openedTime > RESET_TIMEOUT_MS) {
                    // Timeout passed, move to HALF_OPEN
                    commandCircuits.put(entry.getKey(), CircuitState.HALF_OPEN);
                    logger.info("Circuit for '{}' moved to HALF_OPEN state", entry.getKey());
                }
            }
        }

        // Re-check if any circuits are still OPEN within timeout
        anyOpen = commandCircuits.values().stream().anyMatch(s -> s == CircuitState.OPEN);
        if (!anyOpen) {
            return true;
        }

        // Some circuits are still OPEN within timeout - we should block
        // since we can't determine which command is being checked
        logger.debug("Command circuits are open, blocking execution");
        return false;
    }
    
    /**
     * Get the structured error message for LLM when circuit is open.
     */
    public String getCircuitOpenMessage() {
        if (lastCriticalError == null) {
            return "[CIRCUIT_OPEN] Command execution is temporarily disabled due to repeated errors.";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════════════════╗\n");
        sb.append("║          ⚠️  命令执行环境故障 - COMMAND ENVIRONMENT ERROR      ║\n");
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        sb.append("║ 故障类型/Error Type: ").append(pad(lastCriticalError.type.name(), 36)).append("║\n");
        sb.append("║ 连续错误次数/Consecutive Errors: ").append(pad(String.valueOf(lastCriticalError.errorCount), 28)).append("║\n");
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        sb.append("║ 故障诊断/Diagnosis:                                          ║\n");
        sb.append("║ ").append(pad(lastCriticalError.diagnosis, 60)).append("║\n");
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        sb.append("║ 后续行动选项/Recovery Options:                                ║\n");
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        
        int optionNum = 1;
        for (RecoveryOption option : lastCriticalError.recoveryOptions) {
            sb.append("║\n");
            sb.append("║ 选项 ").append(optionNum).append("/Option ").append(optionNum).append(": ").append(option.shortLabel).append("\n");
            sb.append("║ ").append(pad(option.description, 60)).append("║\n");
            sb.append("║ 建议工具/Suggested Tools: ").append(pad(String.join(", ", option.alternativeTools), 33)).append("║\n");
            optionNum++;
        }
        
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        sb.append("║ 💡 提示: 请根据当前任务是否依赖命令行来选择合适的恢复路径      ║\n");
        sb.append("║ 💡 Tip: Choose recovery path based on whether task depends    ║\n");
        sb.append("║    on command execution.                                      ║\n");
        sb.append("╚══════════════════════════════════════════════════════════════╝\n");
        
        return sb.toString();
    }
    
    /**
     * Get current circuit state.
     */
    public CircuitState getState() {
        return state;
    }
    
    /**
     * Check if shell environment is available.
     */
    public boolean isShellEnvironmentAvailable() {
        return shellEnvironmentAvailable;
    }
    
    /**
     * Manually reset the circuit breaker.
     */
    public void reset() {
        state = CircuitState.CLOSED;
        errorTrackers.clear();
        commandCircuits.clear();
        circuitOpenedTimes.clear();
        lastCriticalError = null;
        shellEnvironmentAvailable = true;
        shellEnvironmentError = null;
        logger.info("Circuit breaker manually reset");
    }
    
    /**
     * Get error statistics.
     */
    public Map<String, Integer> getErrorStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        errorTrackers.forEach((sig, tracker) -> stats.put(sig, tracker.getCount()));
        return stats;
    }
    
    // ============== Private Methods ==============
    
    private void tripCircuit(String errorSignature, String errorMessage, ErrorType errorType) {
        // Set per-signature circuit state
        commandCircuits.put(errorSignature, CircuitState.OPEN);
        circuitOpenedTimes.put(errorSignature, System.currentTimeMillis());

        // Update global state for backward compatibility
        state = CircuitState.OPEN;
        shellEnvironmentAvailable = false;

        ErrorTracker tracker = errorTrackers.get(errorSignature);
        int errorCount = tracker != null ? tracker.getCount() : ERROR_THRESHOLD;

        lastCriticalError = new ErrorInfo(
            errorType,
            errorMessage,
            errorSignature,
            errorCount,
            true,
            generateDiagnosis(errorMessage, errorType),
            generateRecoveryOptions(errorType)
        );

        shellEnvironmentError = lastCriticalError.diagnosis;

        logger.error("Circuit breaker TRIPPED for signature: {}! Error type: {}, Count: {}", errorSignature, errorType, errorCount);
        logger.error("Diagnosis: {}", lastCriticalError.diagnosis);
    }
    
    private String extractErrorSignature(String errorMessage) {
        if (errorMessage == null) return "UNKNOWN";
        
        // Normalize error message for signature
        String normalized = errorMessage.toLowerCase().trim();
        
        // Extract key parts
        for (Pattern pattern : ERROR_PATTERNS.keySet()) {
            Matcher m = pattern.matcher(normalized);
            if (m.find()) {
                return pattern.pattern(); // Use pattern as signature
            }
        }
        
        // Fall back to first 100 chars as signature
        return normalized.substring(0, Math.min(100, normalized.length()));
    }
    
    private ErrorType classifyError(String errorMessage) {
        if (errorMessage == null) return ErrorType.UNKNOWN;
        
        String lower = errorMessage.toLowerCase();
        
        if (lower.contains("createprocess") || lower.contains("error=5") || 
            lower.contains("拒绝访问") || lower.contains("access is denied") ||
            lower.contains("permission denied") || lower.contains("权限被拒绝")) {
            return ErrorType.ACCESS_DENIED;
        }
        
        if (lower.contains("是目录") || lower.contains("is a directory") ||
            lower.contains("目录名无效") || lower.contains("directory name is invalid")) {
            return ErrorType.SHELL_MISCONFIGURATION;
        }
        
        if (lower.contains("not found") || lower.contains("找不到") ||
            lower.contains("cannot find")) {
            return ErrorType.PATH_NOT_FOUND;
        }
        
        if (lower.contains("timeout") || lower.contains("超时")) {
            return ErrorType.TIMEOUT;
        }
        
        if (lower.contains("failed to start process") || lower.contains("启动进程失败")) {
            return ErrorType.PROCESS_START_FAILED;
        }
        
        return ErrorType.UNKNOWN;
    }
    
    private String generateDiagnosis(String errorMessage, ErrorType errorType) {
        switch (errorType) {
            case ACCESS_DENIED:
                return diagnoseAccessError(errorMessage);
            case SHELL_MISCONFIGURATION:
                return diagnoseShellMisconfiguration(errorMessage);
            case PATH_NOT_FOUND:
                return diagnosePathNotFound(errorMessage);
            case PROCESS_START_FAILED:
                return diagnoseProcessStartFailure(errorMessage);
            case TIMEOUT:
                return "命令执行超时，可能是进程挂起或系统资源不足。\n" +
                       "Command timed out. Process may be hung or system resources low.";
            default:
                return "未知错误类型。请检查错误消息详情。\n" +
                       "Unknown error type. Please check the error message details.";
        }
    }
    
    private String diagnoseAccessError(String errorMessage) {
        StringBuilder diagnosis = new StringBuilder();
        
        // Try to extract shell path from error
        Pattern pathPattern = Pattern.compile("([A-Z]:\\\\[\\\\\\w\\s.-]+)");
        Matcher m = pathPattern.matcher(errorMessage);
        
        if (m.find()) {
            String path = m.group(1);
            diagnosis.append("Shell 路径配置错误: ").append(path).append("\n");
            diagnosis.append("Shell path misconfiguration: ").append(path).append("\n\n");
            
            java.io.File f = new java.io.File(path);
            if (f.exists() && f.isDirectory()) {
                diagnosis.append("⚠️ 问题: 该路径是目录而非可执行文件！\n");
                diagnosis.append("⚠️ Issue: Path is a directory, not an executable file!\n");
                diagnosis.append("\n建议修复: 使用正确的 bash.exe 路径，例如:\n");
                diagnosis.append("Suggested fix: Use correct bash.exe path, e.g.:\n");
                diagnosis.append("  - Git Bash: C:\\Program Files\\Git\\bin\\bash.exe\n");
                diagnosis.append("  - MinGW: C:\\msys64\\usr\\bin\\bash.exe\n");
                diagnosis.append("  - Cygwin: C:\\cygwin64\\bin\\bash.exe\n");
            } else if (!f.exists()) {
                diagnosis.append("⚠️ 问题: Shell 路径不存在！\n");
                diagnosis.append("⚠️ Issue: Shell path does not exist!\n");
                diagnosis.append("\n建议: 请安装 Git Bash 或其他 Unix-like shell。\n");
                diagnosis.append("Suggestion: Please install Git Bash or other Unix-like shell.\n");
            } else {
                diagnosis.append("⚠️ 问题: 没有执行权限！\n");
                diagnosis.append("⚠️ Issue: No execute permission!\n");
            }
        } else {
            diagnosis.append("访问被拒绝。可能原因:\n");
            diagnosis.append("Access denied. Possible causes:\n");
            diagnosis.append("  1. Shell 路径配置错误\n");
            diagnosis.append("     Shell path is misconfigured\n");
            diagnosis.append("  2. 没有执行权限\n");
            diagnosis.append("     No execute permission\n");
            diagnosis.append("  3. 安全软件拦截\n");
            diagnosis.append("     Security software blocking execution\n");
        }
        
        return diagnosis.toString();
    }
    
    private String diagnoseShellMisconfiguration(String errorMessage) {
        StringBuilder diagnosis = new StringBuilder();
        
        diagnosis.append("Shell 环境配置错误！\n");
        diagnosis.append("Shell environment misconfiguration!\n\n");
        
        // Try to extract problematic path
        Pattern pathPattern = Pattern.compile("([A-Z]:\\\\[\\\\\\w\\s.-]+)");
        Matcher m = pathPattern.matcher(errorMessage);
        
        if (m.find()) {
            String path = m.group(1);
            diagnosis.append("问题路径/Problematic path: ").append(path).append("\n\n");
            
            java.io.File f = new java.io.File(path);
            if (f.isDirectory()) {
                diagnosis.append("⚠️ 该路径是目录，应指向可执行文件！\n");
                diagnosis.append("⚠️ Path is a directory, should point to executable!\n");
                diagnosis.append("\n正确示例/Correct examples:\n");
                diagnosis.append("  Git Bash: C:\\Program Files\\Git\\bin\\bash.exe (不是 C:\\Program Files\\Git)\n");
                diagnosis.append("  MinGW: C:\\msys64\\usr\\bin\\bash.exe (不是 C:\\msys64)\n");
            }
        } else {
            diagnosis.append("请检查 Shell 配置。确保指向正确的可执行文件。\n");
            diagnosis.append("Please check Shell configuration. Ensure it points to correct executable.\n");
        }
        
        return diagnosis.toString();
    }
    
    private String diagnosePathNotFound(String errorMessage) {
        StringBuilder diagnosis = new StringBuilder();
        
        diagnosis.append("Shell 或命令路径未找到！\n");
        diagnosis.append("Shell or command path not found!\n\n");
        
        diagnosis.append("可能原因/Possible causes:\n");
        diagnosis.append("  1. Shell 未安装 (请安装 Git Bash / MinGW / Cygwin)\n");
        diagnosis.append("     Shell not installed (Please install Git Bash / MinGW / Cygwin)\n");
        diagnosis.append("  2. PATH 环境变量配置错误\n");
        diagnosis.append("     PATH environment variable misconfigured\n");
        diagnosis.append("  3. 命令不存在\n");
        diagnosis.append("     Command does not exist\n");
        
        return diagnosis.toString();
    }
    
    private String diagnoseProcessStartFailure(String errorMessage) {
        StringBuilder diagnosis = new StringBuilder();
        
        diagnosis.append("进程启动失败！\n");
        diagnosis.append("Process start failed!\n\n");
        
        diagnosis.append("可能原因/Possible causes:\n");
        diagnosis.append("  1. Shell 环境不可用\n");
        diagnosis.append("     Shell environment unavailable\n");
        diagnosis.append("  2. 系统资源不足\n");
        diagnosis.append("     Insufficient system resources\n");
        diagnosis.append("  3. 安全策略限制\n");
        diagnosis.append("     Security policy restrictions\n");
        
        return diagnosis.toString();
    }
    
    private List<RecoveryOption> generateRecoveryOptions(ErrorType errorType) {
        List<RecoveryOption> options = new ArrayList<>();
        
        // Option A: Use alternative tools
        options.add(new RecoveryOption(
            "跳过命令执行，使用替代工具/Skip command, use alternatives",
            "如果当前任务可以通过文件操作工具完成，选择此选项继续任务。\n" +
            "If current task can be completed with file operation tools, choose this option.",
            Arrays.asList("search_files", "read_file", "list_files", "grep", "write_file", "replace_in_file")
        ));
        
        // Option B: Report to user
        options.add(new RecoveryOption(
            "报告环境问题给用户/Report environment issue to user",
            "如果任务必须使用命令行，选择此选项向用户报告配置问题，等待人工修复。\n" +
            "If task requires command line, choose this option to report configuration issue to user.",
            Collections.singletonList("wait_for_user_action")
        ));
        
        return options;
    }
    
    private String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) {
            return s.substring(0, width - 1) + " ";
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(" ");
        }
        return sb.toString();
    }
    
    // ============== Inner Classes ==============
    
    /**
     * Tracks consecutive errors with the same signature.
     * Uses AtomicInteger for thread-safe count updates.
     */
    private static class ErrorTracker {
        private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);
        private volatile String lastError;
        private volatile long lastErrorTime;

        public void recordError(String errorMessage) {
            count.incrementAndGet();
            lastError = errorMessage;
            lastErrorTime = System.currentTimeMillis();
        }

        public int getCount() { return count.get(); }
        public String getLastError() { return lastError; }
        public long getLastErrorTime() { return lastErrorTime; }
    }
    
    /**
     * Types of errors that can cause circuit to trip.
     */
    public enum ErrorType {
        ACCESS_DENIED,
        SHELL_MISCONFIGURATION,
        PATH_NOT_FOUND,
        PROCESS_START_FAILED,
        TIMEOUT,
        UNKNOWN
    }
    
    /**
     * Information about an error.
     */
    public static class ErrorInfo {
        public final ErrorType type;
        public final String errorMessage;
        public final String errorSignature;
        public final int errorCount;
        public final boolean circuitTripped;
        public final String diagnosis;
        public final List<RecoveryOption> recoveryOptions;
        
        public ErrorInfo(ErrorType type, String errorMessage, String errorSignature,
                        int errorCount, boolean circuitTripped, String diagnosis,
                        List<RecoveryOption> recoveryOptions) {
            this.type = type;
            this.errorMessage = errorMessage;
            this.errorSignature = errorSignature;
            this.errorCount = errorCount;
            this.circuitTripped = circuitTripped;
            this.diagnosis = diagnosis;
            this.recoveryOptions = recoveryOptions;
        }
    }
    
    /**
     * A recovery option presented to the LLM.
     */
    public static class RecoveryOption {
        public final String shortLabel;
        public final String description;
        public final List<String> alternativeTools;
        
        public RecoveryOption(String shortLabel, String description, List<String> alternativeTools) {
            this.shortLabel = shortLabel;
            this.description = description;
            this.alternativeTools = alternativeTools;
        }
    }
}
