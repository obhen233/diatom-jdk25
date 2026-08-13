package com.github.obhen233.cli.provider;

import com.github.obhen233.cli.TerminalUI;
import com.github.obhen233.core.tool.AuthorizedPathManager;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;

import java.util.Set;

/**
 * Auth command provider for authorized paths management.
 */
public class AuthCommandProvider implements CoreCommandProvider, TerminalUI.AuthAware {

    private AuthorizedPathManager authManager;

    public void setAuthManager(AuthorizedPathManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public String getCommandName() {
        return "auth";
    }

    @Override
    public String getDescription() {
        return "{{cli.auth.description}}";
    }

    @Override
    public String getHelp() {
        return "{{cli.auth.help}}";
    }

    @Override
    public String execute(String args, CommandOutput output) {
        if (authManager == null) {
            return "ERROR {{auth.manager_not_available}}";
        }

        String lower = args.toLowerCase().trim();

        if ("auth-list".equals(lower) || "list".equals(lower)) {
            Set<String> authorized = authManager.getAuthorizedPaths();
            if (authorized == null || authorized.isEmpty()) {
                return "INFO {{auth_list_empty}}";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("INFO {{auth_list_title}}");
            for (String path : authorized) {
                sb.append("\n  ").append(path);
            }
            return sb.toString();
        }

        if ("auth-clear".equals(lower) || "clear".equals(lower)) {
            authManager.clearAll();
            return "SUCCESS {{auth_cleared}}";
        }

        return "ERROR {{auth.unknown_command:" . concat(args).concat("}}");
    }
}
