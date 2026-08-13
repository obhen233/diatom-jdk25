package com.github.obhen233.starter;

import com.github.obhen233.cli.provider.ConfigCommandProvider;
import com.github.obhen233.cli.provider.RulesCommandProvider;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.command.tools.ConfigTools;
import com.github.obhen233.core.database.CommandRulesDao;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.CoreCommandRegistry;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.util.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;

import java.util.List;

/**
 * Auto-configuration for CoreCommand SPI support.
 * Registers CoreCommandRegistry bean and initializes command providers with dependencies.
 */
@AutoConfiguration
@DependsOn("i18nInitializer")
public class CoreCommandConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(CoreCommandConfiguration.class);

    /**
     * Create CoreCommandRegistry bean if not already present.
     * Loads all CoreCommandProvider implementations via SPI and registers them.
     * Depends on i18nInitializer to ensure I18n is ready before loading SPI.
     */
    @Bean
    @ConditionalOnMissingBean
    @DependsOn("i18nInitializer")
    public CoreCommandRegistry coreCommandRegistry() {
        CoreCommandRegistry registry = new CoreCommandRegistry();

        // Load all SPI extensions first (I18n must be initialized before this)
        SpiLoader.loadAll();

        // Then get all CoreCommandProvider implementations
        List<CoreCommandProvider> providers = SpiLoader.getAll(CoreCommandProvider.class);
        for (CoreCommandProvider provider : providers) {
            registry.register(provider);
            logger.info("Registered core command: {}", provider.getCommandName());
        }

        logger.info("CoreCommandRegistry initialized with {} commands", registry.size());
        return registry;
    }

    /**
     * Initialize CoreCommandProviders with ReActAgent.
     * This must be done after ReActAgent is available.
     */
    @Bean
    @ConditionalOnMissingBean
    public CoreCommandInitializer coreCommandInitializer(
            CoreCommandRegistry registry,
            @Autowired(required = false) ReActAgent agent) {

        return new CoreCommandInitializer(registry, agent, null);
    }

    /**
     * Initialize ConfigCommandProvider with ConfigTools.
     * This allows config commands to work in IDE context without ReActAgent.
     * ConfigTools may be null if DatabaseManager failed to initialize.
     */
    @Bean
    @ConditionalOnMissingBean
    public ConfigCommandInitializer configCommandInitializer(
            CoreCommandRegistry registry,
            @Autowired(required = false) ConfigTools configTools) {

        return new ConfigCommandInitializer(registry, configTools);
    }

    /**
     * Initializer that sets up command providers with agent context.
     */
    public static class CoreCommandInitializer {
        public CoreCommandInitializer(CoreCommandRegistry registry, ReActAgent agent, ConfigTools configTools) {
            for (CoreCommandProvider provider : registry.getAll()) {
                try {
                    provider.init(agent);
                    logger.debug("Initialized {} with agent", provider.getCommandName());
                } catch (Exception e) {
                    logger.warn("Failed to initialize {} with agent: {}",
                            provider.getCommandName(), e.getMessage());
                }
            }
            logger.info("Core command providers initialized with agent context");
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public RulesCommandInitializer rulesCommandInitializer(
            CoreCommandRegistry registry,
            @Autowired(required = false) DatabaseManager db) {

        return new RulesCommandInitializer(registry, db);
    }

    /**
     * Initializer that injects ConfigTools into ConfigCommandProvider.
     */
    public static class ConfigCommandInitializer {
        public ConfigCommandInitializer(CoreCommandRegistry registry, ConfigTools configTools) {
            if (configTools == null) {
                logger.warn("ConfigTools not available, config commands will not work");
                return;
            }

            for (CoreCommandProvider provider : registry.getAll()) {
                if (provider instanceof ConfigCommandProvider) {
                    try {
                        ((ConfigCommandProvider) provider).setConfigTools(configTools);
                        logger.info("ConfigCommandProvider initialized with ConfigTools");
                    } catch (Exception e) {
                        logger.warn("Failed to inject ConfigTools into ConfigCommandProvider: {}",
                                e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Initializer that injects CommandRulesDao into RulesCommandProvider.
     */
    public static class RulesCommandInitializer {
        public RulesCommandInitializer(CoreCommandRegistry registry, DatabaseManager db) {
            if (db == null) {
                logger.warn("DatabaseManager not available, rules commands will not work");
                return;
            }

            CommandRulesDao dao = new CommandRulesDao(db);
            for (CoreCommandProvider provider : registry.getAll()) {
                if (provider instanceof RulesCommandProvider) {
                    try {
                        ((RulesCommandProvider) provider).setCommandRulesDao(dao);
                        logger.info("RulesCommandProvider initialized with CommandRulesDao");
                    } catch (Exception e) {
                        logger.warn("Failed to inject CommandRulesDao into RulesCommandProvider: {}",
                                e.getMessage());
                    }
                }
            }
        }
    }
}
