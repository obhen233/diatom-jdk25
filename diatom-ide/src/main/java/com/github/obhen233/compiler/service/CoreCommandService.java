package com.github.obhen233.compiler.service;

import com.github.obhen233.compiler.command.IdeCommandOutput;
import com.github.obhen233.compiler.i18n.I18n;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.CoreCommandRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Service for executing core commands in IDE mode.
 * Routes to CoreCommandRegistry SPI which dynamically discovers all CoreCommandProviders.
 * New commands added to the core module via SPI are automatically available here.
 */
@Service
public class CoreCommandService {

    private static final Logger logger = LoggerFactory.getLogger(CoreCommandService.class);

    @Autowired(required = false)
    @Qualifier("coreCommandRegistry")
    private CoreCommandRegistry commandRegistry;

    /**
     * Execute a core command line and return the output.
     *
     * @param commandLine the full command line (e.g., "config set streaming.enabled true")
     * @return the command output, or null if command not recognized
     */
    public String executeCommand(String commandLine) {
        if (commandLine == null || commandLine.trim().isEmpty()) {
            return null;
        }

        String trimmed = commandLine.trim();
        String lower = trimmed.toLowerCase();

        try {
            // Try SPI-based commands first
            if (commandRegistry != null) {
                String output = trySpiCommand(trimmed, lower);
                if (output != null) {
                    return output;
                }
            }

            // Built-in commands that don't go through SPI
            return executeBuiltInCommand(trimmed, lower);

        } catch (Exception e) {
            logger.error("Error executing core command: {}", commandLine, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Try to execute command via SPI-based CoreCommandRegistry
     */
    private String trySpiCommand(String trimmed, String lower) {
        // Extract command name
        String[] parts = trimmed.split("\\s+", 2);
        String commandName = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        // Check if command is registered
        if (!commandRegistry.isRegistered(commandName)) {
            return null; // Not a SPI command
        }

        // Execute via registry
        IdeCommandOutput output = new IdeCommandOutput();
        String spiResult = commandRegistry.execute(trimmed, output);
        if (spiResult != null) {
            return resolveOutputI18n(spiResult);
        }
        return null;
    }

    /**
     * Resolve all {{i18nKey:param}} placeholders in command output.
     */
    private String resolveOutputI18n(String output) {
        if (output == null || output.isEmpty()) {
            return output;
        }
        return com.github.obhen233.compiler.i18n.I18n.resolveTemplate(output);
    }

    /**
     * Execute built-in IDE commands that don't go through SPI.
     * IDE-specific commands only — core module commands should use SPI.
     */
    private String executeBuiltInCommand(String trimmed, String lower) {
        // help command
        if ("help".equals(lower) || "/help".equals(lower)) {
            String lang = I18n.getCurrentLang();
            return getCoreHelp(lang != null ? lang : "zh");
        }

        // streaming command
        if (lower.startsWith("streaming ")) {
            return executeStreamingCommand(trimmed.substring(10).trim());
        }

        // tasks command
        if ("tasks".equals(lower) || lower.startsWith("task ")) {
            return executeTasksCommand(trimmed);
        }

        // snapshot command
        if (lower.startsWith("snapshot ")) {
            return executeSnapshotCommand(trimmed.substring(9).trim());
        }

        // context command
        if (lower.startsWith("context ")) {
            return executeContextCommand(trimmed.substring(8).trim());
        }

        // history command
        if (lower.startsWith("history")) {
            return executeHistoryCommand(trimmed.substring(7).trim());
        }

        // Other core commands - return not recognized
        logger.debug("Core command not recognized: {}", trimmed);
        return null;
    }

    /**
     * Resolve an i18n key through Spring MessageSource if it looks untranslated.
     * Uses the IDE's Spring-managed I18n component which delegates to MessageSource
     * and falls back to CoreI18nAutoConfiguration for core module messages.
     */
    private String resolveI18n(String text) {
        if (text == null) return "";
        // Handle {{key}} or {{key:param}} format
        if (text.startsWith("{{") && text.endsWith("}}")) {
            String inner = text.substring(2, text.length() - 2);
            String[] parts = inner.split(":", -1);
            String key = parts[0];
            String[] params = parts.length > 1 ? java.util.Arrays.copyOfRange(parts, 1, parts.length) : new String[0];
            return I18n.get(key, (Object[]) params);
        }
        // If the text looks like an i18n key (contains dots), try to resolve it
        if (text.contains(".")) {
            String resolved = I18n.get(text);
            return resolved != null ? resolved : text;
        }
        return text;
    }

    /**
     * Get core help text dynamically from SPI-registered command providers.
     * Each provider's getDescription() and getHelp() are called to build the output.
     */
    public String getCoreHelp(String lang) {
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.get("ide.core.commands.title")).append("\n\n");

        if (commandRegistry == null || commandRegistry.size() == 0) {
            sb.append(I18n.get("ide.core.commands.none")).append("\n");
            return sb.toString();
        }

        // List all registered commands with descriptions
        sb.append(I18n.get("ide.core.commands.available")).append("\n");
        for (String cmdName : commandRegistry.getCommandNames()) {
            CoreCommandProvider provider = commandRegistry.get(cmdName);
            if (provider != null) {
                String desc = resolveI18n(provider.getDescription());
                sb.append("  ").append(cmdName);
                int padding = 24 - cmdName.length();
                if (padding < 2) padding = 2;
                for (int i = 0; i < padding; i++) sb.append(' ');
                sb.append(desc).append("\n");
            }
        }
        sb.append("\n");

        // Detailed help from each provider
        sb.append(I18n.get("ide.core.commands.details")).append("\n\n");
        for (String cmdName : commandRegistry.getCommandNames()) {
            CoreCommandProvider provider = commandRegistry.get(cmdName);
            if (provider != null) {
                String help = resolveI18n(provider.getHelp());
                if (!help.trim().isEmpty()) {
                    sb.append(help).append("\n\n");
                }
            }
        }

        // Usage examples
        sb.append(I18n.get("ide.core.commands.usage")).append("\n");
        sb.append("  ").append(I18n.get("ide.core.commands.examplePrompt")).append("\n");
        sb.append("  ").append(I18n.get("ide.core.commands.exampleHelp")).append("\n");
        sb.append("  ").append(I18n.get("ide.core.commands.exampleSkills")).append("\n");

        return sb.toString();
    }

    private String executeStreamingCommand(String args) {
        String lower = args.toLowerCase();
        if ("on".equals(lower)) {
            return I18n.get("ide.streaming.on");
        }
        if ("off".equals(lower)) {
            return I18n.get("ide.streaming.off");
        }
        return I18n.get("ide.streaming.usage");
    }

    private String executeTasksCommand(String command) {
        return "Tasks functionality not yet implemented in IDE mode";
    }

    private String executeSnapshotCommand(String args) {
        return "Snapshot functionality not yet implemented in IDE mode";
    }

    private String executeContextCommand(String args) {
        return "Context functionality not yet implemented in IDE mode";
    }

    private String executeHistoryCommand(String args) {
        if (args.trim().isEmpty()) {
            return "History: 0 commands";
        }
        return "History functionality not yet fully implemented in IDE mode";
    }
}
