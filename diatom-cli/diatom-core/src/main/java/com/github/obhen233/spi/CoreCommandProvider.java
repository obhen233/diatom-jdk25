package com.github.obhen233.spi;

import com.github.obhen233.core.agent.ReActAgent;

/**
 * SPI interface for core commands.
 * Implementations can be discovered via Java ServiceLoader.
 */
public interface CoreCommandProvider {

    /**
     * Get the command name (e.g., "mcp", "config")
     */
    String getCommandName();

    /**
     * Get command description for help text
     */
    String getDescription();

    /**
     * Get detailed help text
     */
    String getHelp();

    /**
     * Execute the command and return output string with {{i18nKey}} placeholders.
     * The caller is responsible for i18n resolution.
     * @param args command arguments (after command name, e.g., "list --global")
     * @param output output abstraction (for real-time feedback, optional)
     * @return output string with {{i18nKey}} placeholders, or null if not handled
     */
    String execute(String args, com.github.obhen233.spi.command.CommandOutput output);

    /**
     * Initialize the command provider with agent context.
     * Called after SPI instantiation but before execute().
     * @param agent the ReActAgent instance
     */
    default void init(ReActAgent agent) {
        // Default empty implementation for providers that don't need agent
    }
}
