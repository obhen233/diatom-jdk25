package com.github.obhen233.spi;

import com.github.obhen233.spi.command.CommandOutput;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry for core commands.
 * Thread-safe for concurrent access.
 */
public class CoreCommandRegistry {

    private final Map<String, CoreCommandProvider> commands = new LinkedHashMap<>();

    /**
     * Register a command provider
     */
    public void register(CoreCommandProvider provider) {
        synchronized (commands) {
            commands.put(provider.getCommandName(), provider);
        }
    }

    /**
     * Get a command by name
     */
    public CoreCommandProvider get(String name) {
        synchronized (commands) {
            return commands.get(name);
        }
    }

    /**
     * Get all registered command names
     */
    public Set<String> getCommandNames() {
        synchronized (commands) {
            return commands.keySet();
        }
    }

    /**
     * Get all registered commands
     */
    public Collection<CoreCommandProvider> getAll() {
        synchronized (commands) {
            return commands.values();
        }
    }

    /**
     * Execute a command line and return output string with {{i18nKey}} placeholders.
     * The caller is responsible for i18n resolution.
     * Parses command name and arguments, delegates to appropriate provider.
     *
     * @param commandLine full command line (e.g., "mcp list --global")
     * @param output output abstraction (for real-time feedback, optional)
     * @return output string, or null if command not recognized
     */
    public String execute(String commandLine, com.github.obhen233.spi.command.CommandOutput output) {
        if (commandLine == null || commandLine.trim().isEmpty()) {
            return null;
        }

        String[] parts = commandLine.trim().split("\\s+", 2);
        String commandName = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        CoreCommandProvider provider;
        synchronized (commands) {
            provider = commands.get(commandName);
        }

        if (provider != null) {
            return provider.execute(args, output);
        }
        return null;
    }

    /**
     * Check if a command is registered
     */
    public boolean isRegistered(String name) {
        synchronized (commands) {
            return commands.containsKey(name);
        }
    }

    /**
     * Get the number of registered commands
     */
    public int size() {
        synchronized (commands) {
            return commands.size();
        }
    }
}
