package com.github.obhen233.cli.provider;

import com.github.obhen233.core.database.HistoryManager;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;

import java.util.List;

/**
 * History command provider.
 */
public class HistoryCommandProvider implements CoreCommandProvider {

    private HistoryManager historyManager;

    public void initHistory(HistoryManager historyManager) {
        this.historyManager = historyManager;
    }

    @Override
    public String getCommandName() {
        return "history";
    }

    @Override
    public String getDescription() {
        return "{{cli.history.description}}";
    }

    @Override
    public String getHelp() {
        return "{{cli.history.help}}";
    }

    @Override
    public String execute(String args, CommandOutput output) {
        if (historyManager == null) {
            return "ERROR {{history.manager_not_available}}";
        }

        String lower = args.toLowerCase().trim();

        // clear-history
        if ("clear-history".equals(lower) || "clear".equals(lower)) {
            historyManager.clearAllHistory();
            return "SUCCESS {{history_cleared}}";
        }

        // history stats
        if ("stats".equals(lower)) {
            return "INFO {{history.stats_not_implemented}}";
        }

        // history export
        if (lower.startsWith("export ")) {
            String file = args.substring(7).trim();
            return "INFO {{history.export_not_implemented:" . concat(file).concat("}}");
        }

        // history <n>
        if (!lower.isEmpty() && !lower.contains(" ")) {
            try {
                int count = Integer.parseInt(lower);
                List<HistoryManager.CommandRecord> history = historyManager.getRecentCommandsWithStats(count);
                StringBuilder sb = new StringBuilder();
                sb.append("INFO {{history.recent_commands:").append(count).append("}}");
                for (HistoryManager.CommandRecord rec : history) {
                    sb.append("\n  ").append(rec.inputText);
                }
                return sb.toString();
            } catch (NumberFormatException e) {
                // Not a number, fall through
            }
        }

        // history alone - Just show count
        int count = historyManager.getHistorySize();
        return "INFO {{history_size:" . concat(String.valueOf(count)).concat("}}");
    }
}
