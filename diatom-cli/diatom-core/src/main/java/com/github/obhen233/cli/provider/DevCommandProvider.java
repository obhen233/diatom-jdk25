package com.github.obhen233.cli.provider;

import com.github.obhen233.core.skill.SystemPromptManager;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;
import com.github.obhen233.util.I18n;

/**
 * Dev mode command provider.
 * <p>
 * Enables/disables development mode which exposes additional internal tools.
 * Only registered when running from the standalone diatom-cli.jar.
 */
public class DevCommandProvider implements CoreCommandProvider {

    private final boolean available;

    public DevCommandProvider(boolean available) {
        this.available = available;
    }

    @Override
    public String getCommandName() {
        return "dev";
    }

    @Override
    public String getDescription() {
        return "{{cli.dev.description}}";
    }

    @Override
    public String getHelp() {
        return "{{cli.dev.help}}";
    }

    @Override
    public String execute(String args, CommandOutput output) {
        if (!available) {
            return "ERROR {{dev_mode_not_available}}";
        }

        String lower = args.toLowerCase().trim();
        if (lower.isEmpty() || "on".equals(lower) || "enable".equals(lower)) {
            SystemPromptManager.enableDevelopmentMode();
            return "SUCCESS {{dev_mode_enabled}}\nINFO {{dev_mode_tip}}";
        }

        if ("off".equals(lower) || "disable".equals(lower) || "exit".equals(lower) || "quit".equals(lower)) {
            String exitMsg = SystemPromptManager.exitDevelopmentModeWithPendingCheck();
            return "SUCCESS " + exitMsg;
        }

        return "ERROR {{cli.dev.usage}}";
    }
}
