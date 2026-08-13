package com.github.obhen233.compiler.config;

import com.github.obhen233.core.database.CommandRulesDao;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.starter.CommandRulesInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * IDE Command Rules Configuration
 *
 * Provides IDE-specific command rules that override core built-in rules.
 * User customizations (source=manual/auto-learned) are always preserved.
 */
@Configuration
public class IdeCommandRulesConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(IdeCommandRulesConfiguration.class);

    @Bean
    public CommandRulesInitializer ideCommandRulesInitializer(DatabaseManager db) {
        return new CommandRulesInitializer(db) {
            @Override
            protected String getInitializerName() {
                return "IdeCommandRulesInitializer";
            }

            @Override
            protected List<CommandRulesDao.CommandRule> createBuiltinRules() {
                List<CommandRulesDao.CommandRule> rules = super.createBuiltinRules();

                // IDE Agent mode: add more development tools
                rules.add(new CommandRulesDao.CommandRule("agent", "allowed", "docker", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("agent", "allowed", "kubectl", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("agent", "allowed", "helm", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("agent", "allowed", "terraform", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("agent", "allowed", "flutter", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("agent", "allowed", "gradle", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("agent", "allowed", "yarn", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("agent", "allowed", "pnpm", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("agent", "allowed", "rustc", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("agent", "allowed", "cargo", "built-in"));

                // IDE Terminal mode: add docker and cloud tools
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "docker", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "docker-compose", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "kubectl", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "helm", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "terraform", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "ansible", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "vagrant", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "flutter", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "dart", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "gradle", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "yarn", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "pnpm", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "bun", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "rustc", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "cargo", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "go", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "make", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "cmake", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "gcc", "built-in"));
                rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", "g++", "built-in"));

                logger.info("IdeCommandRulesInitializer: {} total rules", rules.size());
                return rules;
            }
        };
    }
}
