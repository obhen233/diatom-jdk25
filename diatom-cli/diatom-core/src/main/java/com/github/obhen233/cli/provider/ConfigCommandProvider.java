package com.github.obhen233.cli.provider;

import com.github.obhen233.cli.TerminalUI;
import com.github.obhen233.core.command.tools.ConfigTools;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;

/**
 * Config command provider for CLI.
 * Wraps ConfigTools to provide config commands via CoreCommandProvider SPI.
 */
public class ConfigCommandProvider implements CoreCommandProvider, TerminalUI.ConfigAware {

    private ConfigTools configTools;

    /**
     * Set ConfigTools instance (called via init or setter injection)
     */
    public void setConfigTools(ConfigTools configTools) {
        this.configTools = configTools;
    }

    @Override
    public String getCommandName() {
        return "config";
    }

    @Override
    public String getDescription() {
        return "{{cli.config.description}}";
    }

    @Override
    public String getHelp() {
        return "{{cli.config.help}}";
    }

    @Override
    public String execute(String args, CommandOutput output) {
        if (configTools == null) {
            return "Error: {{config.error.not_available}}";
        }

        String lower = args.toLowerCase();
        String[] parts = args.split("\\s+");

        try {
            if (lower.startsWith("set ")) {
                // config set <key> <value>
                String remaining = args.substring(4).trim();
                int spaceIdx = remaining.indexOf(' ');
                if (spaceIdx <= 0) {
                    return "Error: {{config.error.usage:config set <key> <value>}}";
                }
                String key = remaining.substring(0, spaceIdx).trim();
                String value = remaining.substring(spaceIdx + 1).trim();
                return configTools.configSet("{\"key\": \"" + key + "\", \"value\": \"" + value + "\"}");
            }

            if (lower.startsWith("get ")) {
                // config get <key>
                String key = args.substring(4).trim();
                return configTools.configGet("{\"key\": \"" + key + "\"}");
            }

            if ("list".equals(lower) || lower.isEmpty()) {
                // config list
                return configTools.configList("{}");
            }

            if (lower.startsWith("list ")) {
                // config list <category>
                String category = args.substring(5).trim();
                return configTools.configList("{\"category\": \"" + category + "\"}");
            }

            if (lower.startsWith("reset ")) {
                // config reset <key>
                String key = args.substring(6).trim();
                return configTools.configReset("{\"key\": \"" + key + "\"}");
            }

            // Show help
            return configTools.configHelp("{}");

        } catch (Exception e) {
            return "Error: {{config.error.exception:" + e.getMessage() + "}}";
        }
    }
}