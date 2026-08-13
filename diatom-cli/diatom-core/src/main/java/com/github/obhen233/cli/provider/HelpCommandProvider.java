package com.github.obhen233.cli.provider;

import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;

/**
 * Help command provider.
 */
public class HelpCommandProvider implements CoreCommandProvider {

    @Override
    public String getCommandName() {
        return "help";
    }

    @Override
    public String getDescription() {
        return "{{cli.help.description}}";
    }

    @Override
    public String getHelp() {
        return "{{cli.help.help}}";
    }

    @Override
    public String execute(String args, CommandOutput output) {
        String lang = com.github.obhen233.util.I18n.getLanguage();
        if ("en".equalsIgnoreCase(lang)) {
            return "{{cli.help.diatom_cli}}\n\n{{cli.help.type_help}}";
        } else {
            return "{{cli.help.diatom_cli_zh}}\n\n{{cli.help.type_help_zh}}";
        }
    }
}