package com.github.obhen233.core.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.ai.LlmCommandClassifier;
import com.github.obhen233.core.database.CommandExecutionLogDao;
import com.github.obhen233.core.database.CommandKnowledgeDao;
import com.github.obhen233.core.database.CommandKnowledgeDao.CommandKnowledge;
import com.github.obhen233.core.database.CommandExecutionLogDao.CommandExecutionLog;
import com.github.obhen233.core.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.github.obhen233.util.JsonUtils;

/**
 * Command Knowledge Manager
 *
 * Manages the command knowledge base with in-memory caching for fast lookups.
 * Loads knowledge from SQLite at startup and optionally seeds from JSON.
 */
public class CommandKnowledgeManager {
    private static final Logger logger = LoggerFactory.getLogger(CommandKnowledgeManager.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();

    private final DatabaseManager db;
    private final CommandKnowledgeDao knowledgeDao;
    private final CommandExecutionLogDao executionLogDao;

    // In-memory cache: command -> CommandKnowledge
    private final Map<String, CommandKnowledge> knowledgeCache = new ConcurrentHashMap<>();

    // LLM classifier for self-learning
    private LlmCommandClassifier llmClassifier;

    // Built-in dangerous commands (never changeable)
    private final Set<String> builtinDangerousCommands = new HashSet<>();

    // Permission enum
    public static final String PERMISSION_ALLOW = "ALLOW";
    public static final String PERMISSION_DENY = "DENY";
    public static final String PERMISSION_UNSURE = "UNSURE";

    // Risk level enum
    public static final int RISK_SAFE = 0;
    public static final int RISK_CAUTION = 1;
    public static final int RISK_DANGEROUS = 2;
    public static final int RISK_HIGHLY_DANGEROUS = 3;

    // Source enum
    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_LEARNED = "learned";
    public static final String SOURCE_LLM = "llm";

    public CommandKnowledgeManager(DatabaseManager db) {
        this.db = db;
        this.knowledgeDao = new CommandKnowledgeDao(db);
        this.executionLogDao = new CommandExecutionLogDao(db);
        initializeBuiltinDangerousCommands();
    }

    /**
     * Initialize built-in dangerous commands (hardcoded safety baseline)
     */
    private void initializeBuiltinDangerousCommands() {
        // Commands that can cause data loss or system damage
        builtinDangerousCommands.addAll(Arrays.asList(
            // File deletion
            "rm -rf /",
            "rm -rf /*",
            "rm -rf /",
            "rm -rf ~",
            "rm -rf /home",
            "rm -rf /var",
            "rm -rf /usr",
            "rm -rf /bin",
            "rm -rf /sbin",
            "rm -rf /etc",
            "del /s /q c:\\*",
            "del /s /q c:\\windows\\*",
            "format",
            "format c:",
            "format d:",
            // Disk operations
            "dd if=",
            "fdisk",
            "mkfs",
            "mkfs.ext4",
            "mkfs.ntfs",
            // System control
            "shutdown",
            "shutdown -h",
            "shutdown -r",
            "init 0",
            "init 6",
            "systemctl halt",
            "systemctl poweroff",
            "systemctl reboot",
            // User management
            "userdel",
            "groupdel",
            "usermod -L",
            // Network (potential attack vector)
            "nc -e /bin/sh",
            "nc -e cmd.exe",
            "bash -i >& /dev/tcp/",
            // Package manipulation
            "apt-get remove --purge",
            "yum remove",
            "dnf remove",
            "pacman -Rcs"
        ));
        logger.info("Initialized {} built-in dangerous commands", builtinDangerousCommands.size());
    }

    /**
     * Load knowledge base from database into memory cache
     */
    public void loadFromDatabase() {
        logger.info("Loading command knowledge from database...");
        knowledgeCache.clear();

        List<CommandKnowledge> allKnowledge = knowledgeDao.findAll();
        for (CommandKnowledge knowledge : allKnowledge) {
            knowledgeCache.put(knowledge.command.toLowerCase(), knowledge);
        }

        logger.info("Loaded {} commands into knowledge cache", knowledgeCache.size());
    }

    /**
     * Load seed data from JSON resource file
     */
    public void loadSeedData() {
        try {
            InputStream is = getClass().getResourceAsStream("/command-knowledge.json");
            if (is == null) {
                logger.info("No command-knowledge.json seed file found, skipping");
                return;
            }

            SeedData seedData = mapper.readValue(is, SeedData.class);
            if (seedData.commands == null || seedData.commands.isEmpty()) {
                logger.info("Seed data is empty, skipping");
                return;
            }

            int inserted = 0;
            int updated = 0;

            for (SeedCommand cmd : seedData.commands) {
                CommandKnowledge existing = knowledgeDao.findByCommand(cmd.command);
                if (existing == null) {
                    CommandKnowledge knowledge = new CommandKnowledge(
                        cmd.command,
                        cmd.toolType,
                        cmd.permission != null ? cmd.permission : PERMISSION_ALLOW,
                        cmd.riskLevel
                    );
                    knowledge.source = SOURCE_BUILTIN;
                    knowledge.confidence = 100; // Built-in commands have high confidence
                    knowledgeDao.insertCommandKnowledge(knowledge);
                    inserted++;
                } else if ("builtin".equals(existing.source)) {
                    // Update existing builtin commands
                    existing.toolType = cmd.toolType;
                    existing.permission = cmd.permission != null ? cmd.permission : existing.permission;
                    existing.riskLevel = cmd.riskLevel;
                    knowledgeDao.updateCommandKnowledge(existing);
                    updated++;
                }
            }

            logger.info("Loaded seed data: {} new commands, {} updated", inserted, updated);
            loadFromDatabase();

        } catch (Exception e) {
            logger.warn("Failed to load seed data: {}", e.getMessage());
        }
    }

    /**
     * Set the LLM classifier for self-learning.
     * When set, unknown commands can be automatically classified using LLM.
     */
    public void setLlmClassifier(LlmCommandClassifier classifier) {
        this.llmClassifier = classifier;
        logger.info("LLM classifier set for command self-learning");
    }

    /**
     * Check if LLM classifier is available
     */
    public boolean hasLlmClassifier() {
        return llmClassifier != null;
    }

    /**
     * Learn an unknown command using LLM classification.
     * This is the self-learning flow:
     * 1. Get --help output
     * 2. Call LLM to classify
     * 3. Store result in knowledge base
     * 4. Log detailed info to file and database
     *
     * @param command The command to learn
     * @return true if learning succeeded, false otherwise
     */
    public boolean learnCommandWithLlm(String command) {
        if (llmClassifier == null) {
            logger.warn("LLM classifier not set, cannot learn command: {}", command);
            return false;
        }

        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        // Don't re-learn builtin dangerous commands
        if (isBuiltinDangerous(command)) {
            logger.debug("Skipping LLM learning for built-in dangerous command: {}", command);
            return false;
        }

        long startTime = System.currentTimeMillis();
        String classificationMethod = "llm";

        try {
            logger.info("========================================");
            logger.info("[Command Learning] Starting LLM classification");
            logger.info("[Command Learning] Command: {}", command);
            logger.info("[Command Learning] Timestamp: {}", new java.util.Date());
            logger.info("[Command Learning] Classification Method: {}", classificationMethod);
            logger.info("========================================");

            // Call LLM classifier
            LlmCommandClassifier.ClassificationResult result = llmClassifier.classifyAndLearn(command);

            long duration = System.currentTimeMillis() - startTime;

            if (result != null) {
                // Log detailed result
                logger.info("========================================");
                logger.info("[Command Learning] Classification Result:");
                logger.info("[Command Learning]   Command: {}", result.command);
                logger.info("[Command Learning]   Tool Type: {}", result.toolType);
                logger.info("[Command Learning]   Risk Level: {} (0=safe, 1=caution, 2=dangerous, 3=highly dangerous)", result.riskLevel);
                logger.info("[Command Learning]   Permission: {}", result.permission);
                logger.info("[Command Learning]   Confidence: {}%", 100);
                logger.info("[Command Learning]   Reasoning: {}", result.reasoning);
                logger.info("[Command Learning]   Duration: {}ms", duration);
                logger.info("========================================");

                // Record learning in execution log
                recordLearningLog(command, result.toolType, result.permission, result.riskLevel, result.reasoning, classificationMethod, duration, "success");

                logger.info("[Command Learning] Completed successfully: {} -> permission={}, risk={}",
                    command, result.permission, result.riskLevel);

                // Refresh cache
                loadFromDatabase();
                return true;
            } else {
                logger.error("[Command Learning] Classification returned null result for: {}", command);
                recordLearningLog(command, "unknown", "UNSURE", 1, "Classification returned null", classificationMethod, duration, "failed");
                return false;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("========================================");
            logger.error("[Command Learning] FAILED for command: {}", command);
            logger.error("[Command Learning] Error: {}", e.getMessage());
            logger.error("[Command Learning] Duration: {}ms", duration);
            logger.error("========================================");

            recordLearningLog(command, "unknown", "UNSURE", 1, "Error: " + e.getMessage(), classificationMethod, duration, "failed");
            return false;
        }
    }

    /**
     * Learn an unknown command using LLM classification (async version).
     * Returns immediately, learning happens in background.
     */
    public void learnCommandWithLlmAsync(String command) {
        if (llmClassifier == null) {
            return;
        }

        final String cmd = command;
        Thread learnerThread = new Thread(() -> {
            try {
                learnCommandWithLlm(cmd);
            } catch (Exception e) {
                logger.error("Async learning failed for command: {}", cmd, e);
            }
        }, "CommandLearner-" + command);
        learnerThread.setDaemon(true);
        learnerThread.start();
    }

    /**
     * Check if a command needs LLM evaluation (UNSURE with low confidence)
     */
    public boolean needsLlmEvaluation(String command) {
        CommandPermission perm = getCommandPermission(command);
        return PERMISSION_UNSURE.equals(perm.permission) && perm.confidence < 70;
    }

    /**
     * Check if a command is a built-in dangerous command
     */
    public boolean isBuiltinDangerous(String command) {
        if (command == null) return false;
        String lowerCmd = command.toLowerCase().trim();

        // Check exact matches
        if (builtinDangerousCommands.contains(lowerCmd)) {
            return true;
        }

        // Check patterns
        if (lowerCmd.contains("rm -rf /") && !lowerCmd.contains("-l") && !lowerCmd.contains("--no-preserve-root")) {
            return true;
        }

        // Check rm -rf with wildcard patterns (rm -rf *, rm -rf .*, etc.)
        if (lowerCmd.contains("rm -rf") && (lowerCmd.contains("*") || lowerCmd.contains(".*"))) {
            // Check if it's after cd / (which makes it equivalent to rm -rf /)
            if (lowerCmd.contains("cd /") || lowerCmd.matches(".*cd\\s+/\\s*\\|.*rm\\s+-rf.*")) {
                return true;
            }
            // Also block rm -rf * in general (recursive delete of current directory contents)
            if (lowerCmd.matches(".*rm\\s+-rf\\s+\\*.*")) {
                return true;
            }
        }

        // Check dangerous patterns without safety flags
        if ((lowerCmd.contains("del /s /q") || lowerCmd.contains("rm -rf /")) && !lowerCmd.contains("-l")) {
            return true;
        }

        // Check disk operations
        if (lowerCmd.contains("fdisk") || lowerCmd.contains("mkfs") || lowerCmd.contains("dd if=")) {
            return true;
        }

        // Check pipeline commands - detect dangerous commands within pipelines
        if (lowerCmd.contains("|")) {
            String[] parts = lowerCmd.split("\\|");
            for (String part : parts) {
                String trimmed = part.trim();
                // Check if any pipeline segment contains dangerous commands
                if (containsDangerousPipelinePart(trimmed)) {
                    return true;
                }
            }
        }

        // Check command chaining (&&, ||, ;)
        if (lowerCmd.contains("&&") || lowerCmd.contains("||") || lowerCmd.contains(";")) {
            String[] parts = lowerCmd.split("&&|\\|\\||;");
            for (String part : parts) {
                String trimmed = part.trim();
                if (containsDangerousPipelinePart(trimmed)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check if a command part contains dangerous operations
     */
    private boolean containsDangerousPipelinePart(String cmdPart) {
        if (cmdPart == null || cmdPart.trim().isEmpty()) {
            return false;
        }

        String lower = cmdPart.toLowerCase().trim();

        // Skip cd commands themselves (but check what comes after)
        if (lower.startsWith("cd ")) {
            return false;
        }

        // Check for dangerous deletion commands
        if (lower.contains("rm -rf") || lower.contains("rm -r") || lower.contains("rm -f")) {
            // Block rm -rf with any target (not just /)
            if (lower.contains("rm -rf /") || lower.contains("rm -rf \\*") ||
                lower.contains("rm -rf .*") || lower.matches(".*rm\\s+-rf\\s+.*")) {
                return true;
            }
        }

        if (lower.contains("del /s") || lower.contains("rmdir /s") || lower.contains("rmdir /q")) {
            return true;
        }

        if (lower.contains("format")) {
            return true;
        }

        if (lower.contains("dd if=")) {
            return true;
        }

        if (lower.contains("fdisk") || lower.contains("mkfs")) {
            return true;
        }

        // Check for destructive commands with redirection to destroy files
        boolean hasDeviceRedirect = lower.contains("> /dev/sda") || lower.contains("> /dev/sdb");
        boolean hasRmWithRedirect = lower.contains("rm ") && lower.contains(">");
        if (hasDeviceRedirect || hasRmWithRedirect) {
            return true;
        }

        return false;
    }

    /**
     * Get command permission from knowledge base
     */
    public CommandPermission getCommandPermission(String command) {
        if (command == null) {
            return new CommandPermission(null, PERMISSION_UNSURE, RISK_CAUTION, 0);
        }

        String lowerCmd = command.toLowerCase().trim();

        // First check built-in dangerous commands
        if (isBuiltinDangerous(command)) {
            return new CommandPermission(command, PERMISSION_DENY, RISK_HIGHLY_DANGEROUS, 100);
        }

        // Then check knowledge base cache
        CommandKnowledge knowledge = knowledgeCache.get(lowerCmd);
        if (knowledge != null) {
            return new CommandPermission(
                knowledge.command,
                knowledge.permission,
                knowledge.riskLevel,
                knowledge.confidence
            );
        }

        // Not in knowledge base - return UNSURE
        return new CommandPermission(command, PERMISSION_UNSURE, RISK_CAUTION, 0);
    }

    /**
     * Check if a command is allowed (ALLOW permission with sufficient confidence)
     */
    public boolean isAllowed(String command) {
        CommandPermission perm = getCommandPermission(command);
        return PERMISSION_ALLOW.equals(perm.permission) && perm.confidence >= 50;
    }

    /**
     * Learn a new command (add to knowledge base)
     */
    public void learnCommand(String command, String toolType, String permission, int riskLevel, String source) {
        if (command == null || command.trim().isEmpty()) {
            return;
        }

        CommandKnowledge existing = knowledgeDao.findByCommand(command.toLowerCase());
        if (existing != null) {
            // Update existing
            existing.permission = permission;
            existing.riskLevel = riskLevel;
            existing.confidence = Math.min(100, existing.confidence + 10);
            existing.source = source;
            knowledgeDao.updateCommandKnowledge(existing);
            logger.info("Updated command knowledge: {} -> {} (risk={}, conf={})",
                command, permission, riskLevel, existing.confidence);
        } else {
            // Insert new
            CommandKnowledge knowledge = new CommandKnowledge(
                command.toLowerCase(),
                toolType,
                permission,
                riskLevel
            );
            knowledge.source = source != null ? source : SOURCE_LEARNED;
            knowledge.confidence = 50;
            knowledgeDao.insertCommandKnowledge(knowledge);
            logger.info("Learned new command: {} -> {} (risk={})", command, permission, riskLevel);
        }

        // Update cache
        loadFromDatabase();
    }

    /**
     * Record command execution result for learning
     */
    public void recordExecution(String command, String args, String toolType, String result, int riskAssessed) {
        CommandExecutionLog log = new CommandExecutionLog(command, args, toolType, result, riskAssessed);
        executionLogDao.insertExecutionLog(log);

        // If execution was successful and risk was correctly assessed, increase confidence
        if ("success".equals(result) && riskAssessed < RISK_DANGEROUS) {
            String lowerCmd = command.toLowerCase();
            CommandKnowledge knowledge = knowledgeCache.get(lowerCmd);
            if (knowledge != null && knowledge.confidence < 100) {
                knowledgeDao.updateConfidence(lowerCmd, 5);
            }
        }
    }

    /**
     * Record LLM learning result to database with detailed information.
     * This creates a permanent record of how a command was classified.
     */
    public void recordLearningLog(String command, String toolType, String permission,
                                  int riskLevel, String reasoning, String classificationMethod,
                                  long durationMs, String status) {
        try {
            CommandExecutionLog learningLog = new CommandExecutionLog();
            learningLog.command = command;
            learningLog.toolType = toolType;
            learningLog.permission = permission;
            learningLog.riskLevel = riskLevel;
            learningLog.reasoning = reasoning;
            learningLog.classificationMethod = classificationMethod;
            learningLog.durationMs = durationMs;
            learningLog.status = status;
            learningLog.timestamp = System.currentTimeMillis();

            executionLogDao.insertLearningLog(learningLog);

            logger.debug("[Learning Log] Recorded to database: {} (status={})", command, status);
        } catch (Exception e) {
            logger.error("[Learning Log] Failed to record learning log: {}", command, e);
        }
    }

    /**
     * Record user feedback for a command
     */
    public void recordUserFeedback(String command, String feedback) {
        List<CommandExecutionLog> logs = executionLogDao.findByCommand(command);
        if (!logs.isEmpty()) {
            executionLogDao.updateUserFeedback(logs.get(0).id, feedback);

            // Adjust knowledge based on feedback
            if ("allow".equalsIgnoreCase(feedback) || "approve".equalsIgnoreCase(feedback)) {
                learnCommand(command, null, PERMISSION_ALLOW, RISK_SAFE, SOURCE_LEARNED);
            } else if ("deny".equalsIgnoreCase(feedback) || "reject".equalsIgnoreCase(feedback)) {
                learnCommand(command, null, PERMISSION_DENY, RISK_DANGEROUS, SOURCE_LEARNED);
            }
        }
    }

    /**
     * Get all allowed commands (for whitelist)
     */
    public Set<String> getAllowedCommands() {
        Set<String> allowed = new HashSet<>();
        for (CommandKnowledge knowledge : knowledgeCache.values()) {
            if (PERMISSION_ALLOW.equals(knowledge.permission) && knowledge.confidence >= 50) {
                allowed.add(knowledge.command);
            }
        }
        return allowed;
    }

    /**
     * Get frequently used commands
     */
    public List<CommandKnowledge> getFrequentlyUsed(int limit) {
        return knowledgeDao.findFrequentlyUsed(limit);
    }

    /**
     * Get all commands with low confidence (for LLM learning)
     */
    public List<CommandKnowledge> getLowConfidenceCommands(int limit) {
        return knowledgeDao.findLowConfidence(limit);
    }

    /**
     * Get unknown commands (UNSURE permission)
     */
    public List<CommandKnowledge> getUnknownCommands() {
        return knowledgeDao.findUnknown();
    }

    /**
     * Get all commands
     */
    public List<CommandKnowledge> getAllCommands() {
        return knowledgeDao.findAll();
    }

    /**
     * Get knowledge base statistics
     */
    public KnowledgeStats getStats() {
        KnowledgeStats stats = new KnowledgeStats();
        stats.total = knowledgeDao.getCount();
        stats.builtin = knowledgeDao.getCountBySource(SOURCE_BUILTIN);
        stats.learned = knowledgeDao.getCountBySource(SOURCE_LEARNED);
        stats.llm = knowledgeDao.getCountBySource(SOURCE_LLM);
        stats.allow = knowledgeDao.getCountByPermission(PERMISSION_ALLOW);
        stats.deny = knowledgeDao.getCountByPermission(PERMISSION_DENY);
        stats.unsure = knowledgeDao.getCountByPermission(PERMISSION_UNSURE);
        return stats;
    }

    /**
     * Add or update a command in the knowledge base
     */
    public void addOrUpdateCommand(String command, String toolType, String permission, int riskLevel, int confidence, String source) {
        CommandKnowledge existing = knowledgeDao.findByCommand(command.toLowerCase());
        if (existing != null) {
            existing.toolType = toolType;
            existing.permission = permission;
            existing.riskLevel = riskLevel;
            existing.confidence = confidence;
            existing.source = source;
            knowledgeDao.updateCommandKnowledge(existing);
        } else {
            CommandKnowledge knowledge = new CommandKnowledge(
                command.toLowerCase(),
                toolType,
                permission,
                riskLevel
            );
            knowledge.confidence = confidence;
            knowledge.source = source;
            knowledgeDao.insertCommandKnowledge(knowledge);
        }
        loadFromDatabase();
    }

    /**
     * Delete a command from the knowledge base
     */
    public void deleteCommand(String command) {
        knowledgeDao.deleteCommand(command.toLowerCase());
        knowledgeCache.remove(command.toLowerCase());
    }

    /**
     * Command permission result
     */
    public static class CommandPermission {
        public final String command;
        public final String permission;
        public final int riskLevel;
        public final int confidence;

        public CommandPermission(String command, String permission, int riskLevel, int confidence) {
            this.command = command;
            this.permission = permission;
            this.riskLevel = riskLevel;
            this.confidence = confidence;
        }

        public boolean isAllowed() {
            return PERMISSION_ALLOW.equals(permission) && confidence >= 50;
        }

        public boolean isDenied() {
            return PERMISSION_DENY.equals(permission);
        }

        public boolean isUnsure() {
            return PERMISSION_UNSURE.equals(permission);
        }
    }

    /**
     * Knowledge base statistics
     */
    public static class KnowledgeStats {
        public int total;
        public int builtin;
        public int learned;
        public int llm;
        public int allow;
        public int deny;
        public int unsure;
    }

    // Seed data classes for JSON parsing
    private static class SeedData {
        public int version;
        public String description;
        public List<SeedCommand> commands;
    }

    private static class SeedCommand {
        public String command;
        public String toolType;
        public String permission;
        public int riskLevel;
    }
}
