package com.github.obhen233.cli.provider;

import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;

/**
 * Streaming command provider.
 */
public class StreamingCommandProvider implements CoreCommandProvider {

    @Override
    public String getCommandName() {
        return "streaming";
    }

    @Override
    public String getDescription() {
        return "{{cli.streaming.description}}";
    }

    @Override
    public String getHelp() {
        return "{{cli.streaming.help}}";
    }

    @Override
    public String execute(String args, CommandOutput output) {
        String lower = args.toLowerCase().trim();

        if ("on".equals(lower)) {
            return "SUCCESS {{cli.streaming.on}}";
        }

        if ("off".equals(lower)) {
            return "SUCCESS {{cli.streaming.off}}";
        }

        return "ERROR {{cli.streaming.usage}}";
    }
}