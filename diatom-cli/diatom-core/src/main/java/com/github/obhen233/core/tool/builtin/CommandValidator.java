package com.github.obhen233.core.tool.builtin;

import com.github.obhen233.core.database.CommandRulesDao;
import com.github.obhen233.core.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Command Validator
 *
 * Static rules validator for command execution.
 * Does NOT involve LLM - pure static pattern matching.
 *
 * Validation priority:
 * 1. blocked (highest) - always deny
 * 2. dangerous - always deny
 * 3. allowed (lowest) - only checked when whitelistMode=true
 */
public class CommandValidator {
    private static final Logger logger = LoggerFactory.getLogger(CommandValidator.class);

    public static final String MODE_AGENT = "agent";
    public static final String MODE_TERMINAL = "terminal";

    public static final String TYPE_ALLOWED = "allowed";
    public static final String TYPE_BLOCKED = "blocked";
    public static final String TYPE_DANGEROUS = "dangerous";

    private final String mode;
    private final boolean whitelistMode;

    // Cached rules
    private List<String> blockedPatterns = new ArrayList<>();
    private List<String> dangerousPatterns = new ArrayList<>();
    private List<String> allowedPatterns = new ArrayList<>();

    private CommandRulesDao rulesDao;

    public CommandValidator(String mode, boolean whitelistMode) {
        this.mode = mode;
        this.whitelistMode = whitelistMode;
    }

    public CommandValidator(String mode, boolean whitelistMode, DatabaseManager db) {
        this.mode = mode;
        this.whitelistMode = whitelistMode;
        this.rulesDao = new CommandRulesDao(db);
        loadRules();
    }

    /**
     * Load rules from database
     */
    public void loadRules() {
        if (rulesDao == null) {
            return;
        }

        blockedPatterns.clear();
        dangerousPatterns.clear();
        allowedPatterns.clear();

        List<CommandRulesDao.CommandRule> rules = rulesDao.findByMode(mode);
        for (CommandRulesDao.CommandRule rule : rules) {
            if (!rule.enabled) {
                continue;
            }
            switch (rule.type) {
                case TYPE_BLOCKED:
                    blockedPatterns.add(rule.pattern);
                    break;
                case TYPE_DANGEROUS:
                    dangerousPatterns.add(rule.pattern);
                    break;
                case TYPE_ALLOWED:
                    allowedPatterns.add(rule.pattern);
                    break;
            }
        }

        logger.info("Loaded rules for mode {}: blocked={}, dangerous={}, allowed={}",
                mode, blockedPatterns.size(), dangerousPatterns.size(), allowedPatterns.size());
    }

    /**
     * Validate a command
     * @param command The command to validate
     * @return ValidationResult
     */
    public ValidationResult validate(String command) {
        if (command == null || command.trim().isEmpty()) {
            return ValidationResult.denied("empty command");
        }

        String lower = command.toLowerCase();

        // 0. Special handling for pipe/chaining commands
        // Split by command separators and validate each part
        ValidationResult pipeResult = validatePipeCommand(lower);
        if (pipeResult != null) {
            return pipeResult;
        }

        // 1. Check blocked patterns (highest priority)
        for (String pattern : blockedPatterns) {
            if (matchesPattern(lower, pattern)) {
                return ValidationResult.denied("blocked: " + pattern);
            }
        }

        // 2. Check dangerous patterns
        for (String pattern : dangerousPatterns) {
            if (matchesPattern(lower, pattern)) {
                return ValidationResult.denied("dangerous: " + pattern);
            }
        }

        // 3. Check allowed (only if whitelistMode is enabled)
        if (whitelistMode) {
            boolean isAllowed = false;
            for (String pattern : allowedPatterns) {
                if (matchesCommand(lower, pattern)) {
                    isAllowed = true;
                    break;
                }
            }
            if (!isAllowed) {
                return ValidationResult.denied("not in whitelist");
            }
        }

        return ValidationResult.allowed();
    }

    /**
     * Validate pipe/chaining commands by checking each part
     * Returns null if not a pipe command (continue normal validation),
     * or returns ValidationResult if a dangerous pattern is found
     */
    private ValidationResult validatePipeCommand(String command) {
        // Check if command contains pipe or chaining operators
        if (!command.contains("|") && !command.contains("&&") && !command.contains("||") && !command.contains(";")) {
            return null; // Not a pipe command, continue normal validation
        }

        // Split by pipe first (highest priority)
        String[] pipeParts = command.split("\\|");

        for (String pipePart : pipeParts) {
            String part = pipePart.trim();
            if (part.isEmpty()) {
                continue;
            }

            // Check each pipe part against blocked/dangerous patterns
            // For chained commands (&&, ||, ;), split and check each sub-part
            String[] chainParts = part.split("&&|\\|\\||;");

            for (String chainPart : chainParts) {
                String subPart = chainPart.trim();
                if (subPart.isEmpty()) {
                    continue;
                }

                // Check blocked patterns in this sub-part
                for (String blocked : blockedPatterns) {
                    if (subPart.contains(blocked.toLowerCase())) {
                        return ValidationResult.denied("blocked in pipe: " + blocked);
                    }
                }

                // Check dangerous patterns in this sub-part
                for (String dangerous : dangerousPatterns) {
                    if (subPart.contains(dangerous.toLowerCase())) {
                        return ValidationResult.denied("dangerous in pipe: " + dangerous);
                    }
                }
            }
        }

        return null; // No dangerous patterns found, continue normal validation
    }

    /**
     * Check if command matches a pattern
     */
    private boolean matchesPattern(String command, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }

        // Handle special patterns
        if (pattern.equals("&&") || pattern.equals("||") || pattern.equals(";")) {
            // These are command separators - check if they appear in the command
            return command.contains(pattern);
        }

        if (pattern.equals("`") || pattern.equals("$(")) {
            return command.contains(pattern);
        }

        // Regular substring match for other patterns
        return command.contains(pattern.toLowerCase());
    }

    /**
     * Check if command matches an allowed pattern
     */
    private boolean matchesCommand(String command, String allowedPattern) {
        if (allowedPattern == null || allowedPattern.isEmpty()) {
            return false;
        }

        // Extract the base command
        String baseCmd = extractBaseCommand(command);

        // Direct match
        if (baseCmd.equals(allowedPattern.toLowerCase())) {
            return true;
        }

        // Check if the command starts with the allowed pattern (for commands with paths)
        String lowerPattern = allowedPattern.toLowerCase();
        if (baseCmd.startsWith(lowerPattern + " ") || baseCmd.startsWith(lowerPattern + "/")) {
            return true;
        }

        return false;
    }

    /**
     * Extract base command from a command string
     */
    private String extractBaseCommand(String command) {
        if (command == null || command.isEmpty()) {
            return "";
        }

        // Remove leading/trailing whitespace
        command = command.trim();

        // Split by whitespace
        String[] parts = command.split("\\s+");

        if (parts.length == 0) {
            return "";
        }

        String baseCmd = parts[0];

        // Handle git subcommands like "git status" -> "git"
        if (baseCmd.equals("git") && parts.length > 1) {
            return "git";
        }

        // Handle mvn subcommands
        if (baseCmd.equals("mvn") && parts.length > 1) {
            return "mvn";
        }

        // Handle npm subcommands
        if (baseCmd.equals("npm") && parts.length > 1) {
            return "npm";
        }

        return baseCmd;
    }

    /**
     * Add blocked pattern at runtime
     */
    public void addBlockedPattern(String pattern) {
        if (pattern != null && !pattern.isEmpty()) {
            blockedPatterns.add(pattern);
        }
    }

    /**
     * Add dangerous pattern at runtime
     */
    public void addDangerousPattern(String pattern) {
        if (pattern != null && !pattern.isEmpty()) {
            dangerousPatterns.add(pattern);
        }
    }

    /**
     * Add allowed pattern at runtime
     */
    public void addAllowedPattern(String pattern) {
        if (pattern != null && !pattern.isEmpty()) {
            allowedPatterns.add(pattern);
        }
    }

    // Getters
    public String getMode() {
        return mode;
    }

    public boolean isWhitelistMode() {
        return whitelistMode;
    }

    public List<String> getBlockedPatterns() {
        return Collections.unmodifiableList(blockedPatterns);
    }

    public List<String> getDangerousPatterns() {
        return Collections.unmodifiableList(dangerousPatterns);
    }

    public List<String> getAllowedPatterns() {
        return Collections.unmodifiableList(allowedPatterns);
    }

    /**
     * Validation result
     */
    public static class ValidationResult {
        private final boolean allowed;
        private final String reason;

        public ValidationResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static ValidationResult allowed() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult denied(String reason) {
            return new ValidationResult(false, reason);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public boolean isDenied() {
            return !allowed;
        }

        public String getReason() {
            return reason;
        }
    }

    /**
     * Builder for creating validators with built-in rules
     */
    public static class Builder {
        private String mode = MODE_AGENT;
        private boolean whitelistMode = true;
        private boolean loadBuiltin = true;

        public Builder mode(String mode) {
            this.mode = mode;
            return this;
        }

        public Builder whitelistMode(boolean whitelistMode) {
            this.whitelistMode = whitelistMode;
            return this;
        }

        public Builder loadBuiltin(boolean loadBuiltin) {
            this.loadBuiltin = loadBuiltin;
            return this;
        }

        public CommandValidator build() {
            CommandValidator validator = new CommandValidator(mode, whitelistMode);
            if (loadBuiltin) {
                loadBuiltinRules(validator);
            }
            return validator;
        }

        private void loadBuiltinRules(CommandValidator validator) {
            if (MODE_AGENT.equals(mode)) {
                // Agent mode - strict rules
                for (String p : Arrays.asList("rm -rf /", "format", "fdisk", "mkfs", "dd if=")) {
                    validator.addBlockedPattern(p);
                }
                for (String p : Arrays.asList("rm -rf", "del /s")) {
                    validator.addDangerousPattern(p);
                }
                for (String p : Arrays.asList("mvn", "git", "java", "javac", "npm", "node", "go", "python", "python3")) {
                    validator.addAllowedPattern(p);
                }
            } else if (MODE_TERMINAL.equals(mode)) {
                // Terminal mode - more permissive
                for (String p : Arrays.asList("rm -rf /", "format", "fdisk", "mkfs")) {
                    validator.addBlockedPattern(p);
                }
                for (String p : Arrays.asList("&&", "||", ";", "$|", "`")) {
                    validator.addDangerousPattern(p);
                }
                for (String p : Arrays.asList("ls", "dir", "cat", "echo", "pwd", "cd", "git", "mvn", "npm", "node", "python", "curl", "clear", "docker", "docker-compose")) {
                    validator.addAllowedPattern(p);
                }
            }
        }
    }
}
