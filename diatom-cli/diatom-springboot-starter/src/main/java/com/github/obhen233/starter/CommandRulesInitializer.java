package com.github.obhen233.starter;

import com.github.obhen233.core.database.CommandRulesDao;
import com.github.obhen233.core.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Command Rules Initializer for Spring Boot Starter
 *
 * This class provides an extension point for IDE projects to customize
 * command validation rules. Subclasses can override createBuiltinRules()
 * to provide project-specific rules.
 *
 * Initialization behavior:
 * - Only executes once per database lifecycle (via static flag)
 * - Only inserts rules if the database is empty
 * - User customizations are preserved - this never overwrites existing rules
 *
 * For config data: only UPDATE, never DELETE
 */
public class CommandRulesInitializer {
    private static final Logger logger = LoggerFactory.getLogger(CommandRulesInitializer.class);

    // Track initialization status - set once after first initialization
    private static volatile boolean initialized = false;

    protected final DatabaseManager db;
    private CommandRulesDao rulesDao;

    public CommandRulesInitializer(DatabaseManager db) {
        this.db = db;
        if (db != null) {
            this.rulesDao = new CommandRulesDao(db);
        }
    }

    /**
     * Get the initializer name for logging
     */
    protected String getInitializerName() {
        return "CommandRulesInitializer";
    }

    /**
     * Initialize command rules
     * Only executes once per database lifecycle
     *
     * Only inserts rules if the database is empty.
     * Built-in rules (source=built-in) from core can be overwritten by IDE.
     * User customizations (source=manual/auto-learned) are always preserved.
     */
    public synchronized void initialize() {
        if (initialized) {
            logger.debug("{}: Already initialized, skipping", getInitializerName());
            return;
        }

        if (rulesDao == null) {
            logger.warn("DatabaseManager is null, skipping command rules initialization");
            return;
        }

        int existingCount = rulesDao.getCount();
        if (existingCount > 0) {
            // Check if only built-in rules exist (can be overwritten)
            // User customizations (source=manual/auto-learned) are preserved
            int builtinCount = rulesDao.findBySource("built-in").size();
            int userCount = existingCount - builtinCount;

            if (userCount > 0) {
                logger.info("{}: {} rules exist (including {} user rules), skipping (preserving user customizations)",
                        getInitializerName(), existingCount, userCount);
                initialized = true;
                return;
            }

            // Only built-in rules exist - IDE can overwrite
            logger.info("{}: {} built-in rules exist, IDE will overwrite with custom rules",
                    getInitializerName(), existingCount);
            rulesDao.deleteBySource("built-in");
        }

        // Insert rules
        List<CommandRulesDao.CommandRule> rules = createBuiltinRules();
        for (CommandRulesDao.CommandRule rule : rules) {
            rulesDao.insert(rule);
        }

        initialized = true;
        logger.info("{}: Initialized {} rules", getInitializerName(), rules.size());
    }

    /**
     * Create built-in rules.
     * Subclasses can override to provide custom rules.
     */
    protected List<CommandRulesDao.CommandRule> createBuiltinRules() {
        List<CommandRulesDao.CommandRule> rules = new ArrayList<>();

        // Agent mode rules
        for (String p : Arrays.asList("rm -rf /", "format", "fdisk", "mkfs", "dd if=")) {
            rules.add(new CommandRulesDao.CommandRule("agent", "blocked", p, "built-in"));
        }
        for (String p : Arrays.asList("rm -rf", "del /s")) {
            rules.add(new CommandRulesDao.CommandRule("agent", "dangerous", p, "built-in"));
        }
        for (String p : Arrays.asList("mvn", "git", "java", "javac", "npm", "node", "go", "python", "python3")) {
            rules.add(new CommandRulesDao.CommandRule("agent", "allowed", p, "built-in"));
        }

        // Terminal mode rules
        for (String p : Arrays.asList("rm -rf /", "format", "fdisk", "mkfs")) {
            rules.add(new CommandRulesDao.CommandRule("terminal", "blocked", p, "built-in"));
        }
        for (String p : Arrays.asList("&&", "||", ";", "$|", "`")) {
            rules.add(new CommandRulesDao.CommandRule("terminal", "dangerous", p, "built-in"));
        }
        for (String p : Arrays.asList("ls", "dir", "cat", "echo", "pwd", "cd", "git", "mvn", "npm", "node", "python", "curl", "clear")) {
            rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", p, "built-in"));
        }

        return rules;
    }

    /**
     * Reset initialization status (for testing only)
     */
    protected static void resetInitialization() {
        initialized = false;
    }
}
