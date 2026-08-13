package com.github.obhen233.core.tool.builtin;

import com.github.obhen233.core.skill.SystemPromptManager;
import com.github.obhen233.core.tool.annotation.ToolMethod;

/**
 * System-level tools that are always available.
 * These tools provide system information and status checks.
 */
public class SystemTools {

    @ToolMethod(name = "check_self_update",
                description = "Check if self-update is available. Returns status and instructions for enabling it if not available.",
                parametersSchema = "{}")
    public String checkSelfUpdate() {
        StringBuilder sb = new StringBuilder();

        // Check standalone JAR mode
        boolean isStandalone = "true".equals(System.getProperty("diatom.standalone.jar"));
        if (!isStandalone) {
            sb.append("Self-update is only available in standalone JAR mode.").append(System.lineSeparator());
            sb.append("Current mode: IDE integration or development mode.").append(System.lineSeparator());
            sb.append("To enable self-update, run the standalone diatom-cli.jar.").append(System.lineSeparator());
            return sb.toString();
        }

        // Check development mode
        if (!SystemPromptManager.isDevelopmentMode()) {
            sb.append("Development mode is not enabled.").append(System.lineSeparator());
            sb.append("Use 'dev' command to enable it, then try again.").append(System.lineSeparator());
            return sb.toString();
        }

        // All checks passed
        sb.append("Self-update is available.").append(System.lineSeparator());
        sb.append("Use init_sources() to check source status.").append(System.lineSeparator());
        sb.append("Use extract_sources() to extract source code for editing.").append(System.lineSeparator());
        return sb.toString();
    }
}
