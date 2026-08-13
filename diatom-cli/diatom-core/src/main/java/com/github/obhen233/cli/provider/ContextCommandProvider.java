package com.github.obhen233.cli.provider;

import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;

/**
 * Context command provider.
 */
public class ContextCommandProvider implements CoreCommandProvider {

    private ReActAgent agent;

    @Override
    public String getCommandName() {
        return "context";
    }

    @Override
    public String getDescription() {
        return "{{cli.context.description}}";
    }

    @Override
    public String getHelp() {
        return "{{cli.context.help}}";
    }

    @Override
    public void init(ReActAgent agent) {
        this.agent = agent;
    }

    @Override
    public String execute(String args, CommandOutput output) {
        if (agent == null) {
            return "ERROR {{context.agent_not_initialized}}";
        }

        String lower = args.toLowerCase().trim();

        // context --compress
        if ("--compress".equals(lower)) {
            return "INFO {{context.compress_handled}}";
        }

        // context --history
        if ("--history".equals(lower)) {
            return "INFO {{context.history_not_implemented}}";
        }

        // context refresh
        if ("refresh".equals(lower) || "context refresh".equals(lower)) {
            agent.invalidateProjectContext();
            return "SUCCESS {{context_refreshed}}";
        }

        // context messages
        if ("messages".equals(lower)) {
            int count = agent.getConversationHistory().size();
            return "INFO {{context.messages:" . concat(String.valueOf(count)).concat("}}");
        }

        // context tokens
        if ("tokens".equals(lower)) {
            return "INFO {{context.tokens_not_implemented}}";
        }

        // context tools
        if ("tools".equals(lower)) {
            int count = agent.getAvailableTools().size();
            return "INFO {{context.tools:" . concat(String.valueOf(count)).concat("}}");
        }

        // context alone - show basic context info
        if ("context".equals(lower) || lower.isEmpty()) {
            String taskId = agent.getCurrentTaskId();
            int msgCount = agent.getConversationHistory().size();
            int toolCount = agent.getAvailableTools().size();
            return "INFO\n=== Context ===\nTask: " + (taskId != null ? taskId : "(none)")
                + "\nMessages: " + msgCount + "\nTools: " + toolCount
                + "\n{{context.use_refresh}}";
        }

        return "ERROR {{context.unknown_command:" . concat(args).concat("}}");
    }
}
