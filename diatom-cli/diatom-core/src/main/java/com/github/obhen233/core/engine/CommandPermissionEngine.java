package com.github.obhen233.core.engine;

import com.github.obhen233.core.knowledge.CommandKnowledgeManager;
import com.github.obhen233.core.knowledge.CommandKnowledgeManager.CommandPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Command Permission Engine
 *
 * Core permission checking engine that combines:
 * 1. Built-in dangerous command detection (hardcoded baseline)
 * 2. Knowledge base lookup (SQLite cache)
 * 3. LLM judgment for unknown commands (optional)
 */
public class CommandPermissionEngine {
    private static final Logger logger = LoggerFactory.getLogger(CommandPermissionEngine.class);

    private final CommandKnowledgeManager knowledgeManager;

    // High-risk patterns that are always denied
    private static final Set<String> HIGH_RISK_PATTERNS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "rm -rf /",
            "rm -rf /*",
            "rm -rf *",
            "rm -rf .*",
            "del /s /q",
            "format",
            "fdisk",
            "dd if=",
            "mkfs"
        ))
    );

    public CommandPermissionEngine(CommandKnowledgeManager knowledgeManager) {
        this.knowledgeManager = knowledgeManager;
    }

    /**
     * Check if a command is permitted to run
     */
    public PermissionResult checkPermission(String command) {
        if (command == null || command.trim().isEmpty()) {
            return PermissionResult.deny("Empty command", 100);
        }

        String trimmedCommand = command.trim();

        // Step 1: Check built-in dangerous commands (always denied)
        if (knowledgeManager.isBuiltinDangerous(trimmedCommand)) {
            logger.debug("Command denied (builtin dangerous): {}", trimmedCommand);
            return PermissionResult.deny("Built-in dangerous command", 100);
        }

        // Step 2: Check high-risk patterns
        if (containsHighRiskPattern(trimmedCommand)) {
            logger.debug("Command denied (high risk pattern): {}", trimmedCommand);
            return PermissionResult.deny("High risk pattern detected", 95);
        }

        // Step 3: Check knowledge base
        CommandPermission kp = knowledgeManager.getCommandPermission(trimmedCommand);

        if (kp.isDenied()) {
            logger.debug("Command denied (knowledge base): {} (conf={})", trimmedCommand, kp.confidence);
            return PermissionResult.deny("Command denied in knowledge base", kp.confidence);
        }

        if (kp.isAllowed()) {
            logger.debug("Command allowed (knowledge base): {} (conf={})", trimmedCommand, kp.confidence);
            return PermissionResult.allow(kp.riskLevel, kp.confidence, "Known command");
        }

        // UNSURE - needs evaluation
        if (kp.isUnsure()) {
            if (kp.confidence >= 70) {
                // High enough confidence to allow with caution
                logger.debug("Command allowed (UNSURE, high confidence): {} (conf={})", trimmedCommand, kp.confidence);
                return PermissionResult.allowWithCaution(kp.riskLevel, kp.confidence, "Uncertain command, allowing with caution");
            } else {
                // Low confidence - needs LLM evaluation or user confirmation
                logger.debug("Command needs evaluation: {} (conf={})", trimmedCommand, kp.confidence);
                return PermissionResult.needsEvaluation(trimmedCommand, kp.riskLevel, kp.confidence, "Command needs LLM evaluation");
            }
        }

        // Default: deny unknown commands
        return PermissionResult.needsEvaluation(trimmedCommand, CommandKnowledgeManager.RISK_CAUTION, 0, "Unknown command");
    }

    /**
     * Check if command contains high-risk patterns
     */
    private boolean containsHighRiskPattern(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        String lower = command.toLowerCase();

        // Check direct patterns first
        for (String pattern : HIGH_RISK_PATTERNS) {
            if (lower.contains(pattern.toLowerCase())) {
                return true;
            }
        }

        // Check pipeline commands for dangerous parts
        if (lower.contains("|")) {
            String[] parts = lower.split("\\|");
            for (String part : parts) {
                String trimmed = part.trim();
                if (containsHighRiskPatternPart(trimmed)) {
                    return true;
                }
            }
        }

        // Check command chaining
        if (lower.contains("&&") || lower.contains("||") || lower.contains(";")) {
            String[] parts = lower.split("&&|\\|\\||;");
            for (String part : parts) {
                String trimmed = part.trim();
                if (containsHighRiskPatternPart(trimmed)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check if a command part contains high-risk patterns
     */
    private boolean containsHighRiskPatternPart(String cmdPart) {
        if (cmdPart == null || cmdPart.trim().isEmpty()) {
            return false;
        }

        String lower = cmdPart.toLowerCase().trim();

        // Skip cd commands
        if (lower.startsWith("cd ")) {
            return false;
        }

        // Check rm -rf with any argument (not just /)
        if (lower.contains("rm -rf")) {
            // Block rm -rf with any path that could be destructive
            if (lower.matches(".*rm\\s+-rf\\s+[\\*/\\.].*") ||
                lower.contains("rm -rf /") ||
                lower.contains("rm -rf \\*") ||
                lower.contains("rm -rf /home") ||
                lower.contains("rm -rf /var") ||
                lower.contains("rm -rf /usr") ||
                lower.contains("rm -rf /etc") ||
                lower.contains("rm -rf /bin") ||
                lower.contains("rm -rf /sbin")) {
                return true;
            }
        }

        // Check other dangerous commands
        if (lower.contains("del /s") || lower.contains("format") ||
            lower.contains("fdisk") || lower.contains("dd if=") || lower.contains("mkfs")) {
            return true;
        }

        return false;
    }

    /**
     * Check if a command is a read-only operation
     */
    public boolean isReadOnlyCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        String lowerCmd = command.toLowerCase().trim();

        // Git read-only commands
        if (lowerCmd.startsWith("git status") || lowerCmd.startsWith("git log") ||
            lowerCmd.startsWith("git show") || lowerCmd.startsWith("git diff") ||
            lowerCmd.startsWith("git branch") || lowerCmd.startsWith("git stash list") ||
            lowerCmd.startsWith("git reflog")) {
            return true;
        }

        // Other read-only patterns
        String[] readOnlyCmds = {"cat ", "head ", "tail ", "less ", "more ", "grep ", "find ", "ls ", "dir ", "pwd", "wc "};
        for (String cmd : readOnlyCmds) {
            if (lowerCmd.contains(cmd) && !lowerCmd.contains(">") && !lowerCmd.contains("|")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if command contains write operations
     */
    public boolean containsWriteOperation(String command) {
        if (command == null) return false;
        String lower = command.toLowerCase();

        // Check for redirect operators
        if (lower.contains(" > ") || lower.contains(" >> ") || lower.contains(" 2>") || lower.contains(" &>")) {
            return true;
        }

        // Check for pipe with write commands
        if (lower.contains("| rm ") || lower.contains("| del ") || lower.contains("| mv ") ||
            lower.contains("| cp ") || lower.contains("| chmod ") || lower.contains("| chown ")) {
            return true;
        }

        return false;
    }

    /**
     * Get the tool type for a command based on command name
     */
    public String inferToolType(String command) {
        if (command == null) return null;
        String lower = command.toLowerCase().trim();

        if (lower.startsWith("git ") || lower.equals("git")) return "git";
        if (lower.startsWith("mvn ") || lower.equals("mvn")) return "maven";
        if (lower.startsWith("npm ") || lower.equals("npm") || lower.startsWith("npx ") || lower.equals("npx")) return "npm";
        if (lower.startsWith("docker ") || lower.equals("docker")) return "docker";
        if (lower.startsWith("java ") || lower.equals("java") || lower.startsWith("javac ") || lower.equals("javac")) return "java";
        if (lower.startsWith("go ") || lower.equals("go")) return "go";
        if (lower.startsWith("python") || lower.equals("python") || lower.startsWith("python3")) return "python";
        if (lower.startsWith("curl ") || lower.equals("curl")) return "curl";
        if (lower.startsWith("wget ") || lower.equals("wget")) return "wget";

        return "shell";
    }

    /**
     * Evaluate a command using LLM and learn the result.
     * This is the self-learning flow for unknown commands.
     *
     * @param command The command to evaluate
     * @return true if learning succeeded, false otherwise
     */
    public boolean evaluateAndLearn(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        if (!knowledgeManager.hasLlmClassifier()) {
            logger.warn("LLM classifier not available, cannot evaluate command: {}", command);
            return false;
        }

        logger.info("Evaluating command with LLM: {}", command);
        return knowledgeManager.learnCommandWithLlm(command);
    }

    /**
     * Check if a command needs LLM evaluation
     */
    public boolean needsLlmEvaluation(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        // Check built-in dangerous first
        if (knowledgeManager.isBuiltinDangerous(command)) {
            return false;
        }

        // Check high-risk patterns
        if (containsHighRiskPattern(command)) {
            return false;
        }

        // Check knowledge base
        CommandPermission kp = knowledgeManager.getCommandPermission(command);
        return kp.isUnsure() && kp.confidence < 70;
    }

    /**
     * Permission result
     */
    public static class PermissionResult {
        public final Status status;
        public final String reason;
        public final int riskLevel;
        public final int confidence;
        public final String command;  // For needsEvaluation

        public enum Status {
            ALLOW,
            ALLOW_WITH_CAUTION,
            DENY,
            NEEDS_EVALUATION
        }

        private PermissionResult(Status status, String reason, int riskLevel, int confidence, String command) {
            this.status = status;
            this.reason = reason;
            this.riskLevel = riskLevel;
            this.confidence = confidence;
            this.command = command;
        }

        public static PermissionResult allow(int riskLevel, int confidence, String reason) {
            return new PermissionResult(Status.ALLOW, reason, riskLevel, confidence, null);
        }

        public static PermissionResult allowWithCaution(int riskLevel, int confidence, String reason) {
            return new PermissionResult(Status.ALLOW_WITH_CAUTION, reason, riskLevel, confidence, null);
        }

        public static PermissionResult deny(String reason, int confidence) {
            return new PermissionResult(Status.DENY, reason, CommandKnowledgeManager.RISK_HIGHLY_DANGEROUS, confidence, null);
        }

        public static PermissionResult needsEvaluation(String command, int riskLevel, int confidence, String reason) {
            return new PermissionResult(Status.NEEDS_EVALUATION, reason, riskLevel, confidence, command);
        }

        public boolean isAllowed() {
            return status == Status.ALLOW || status == Status.ALLOW_WITH_CAUTION;
        }

        public boolean isDenied() {
            return status == Status.DENY;
        }

        public boolean needsEvaluation() {
            return status == Status.NEEDS_EVALUATION;
        }
    }
}
