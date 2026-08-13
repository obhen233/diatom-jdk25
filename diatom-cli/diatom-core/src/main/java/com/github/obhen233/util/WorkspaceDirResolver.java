package com.github.obhen233.util;

import com.github.obhen233.config.AppConfig;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Unified workspace directory resolver with the following priority chain:
 * <ol>
 *   <li>{@code --workspace-dir / -w} CLI flag (stored as {@code diatom.ws.cli.arg} system property)</li>
 *   <li>{@code -Dworkspace.dir} system property</li>
 *   <li>{@code DIATOM_WORKSPACE_DIR} environment variable</li>
 *   <li>{@code workspace.dir} config property (with {@code ${user.dir}} placeholder support)</li>
 *   <li>{@code diatom.original.user.dir} system property, falling back to {@code user.dir} (default)</li>
 * </ol>
 */
public class WorkspaceDirResolver {

    private static final String CLI_ARG_PROP = "diatom.ws.cli.arg";
    private static final String WORKSPACE_DIR_PROP = "workspace.dir";

    /**
     * Execute the full priority chain to resolve the workspace directory.
     *
     * @param config AppConfig instance for reading config properties
     * @return resolved, normalized absolute path
     */
    public static String resolve(AppConfig config) {
        // Priority 1: --workspace-dir / -w CLI flag
        String cliArg = System.getProperty(CLI_ARG_PROP);
        if (cliArg != null && !cliArg.isEmpty()) {
            return normalize(cliArg);
        }

        // Priority 2: -Dworkspace.dir system property
        String sysProp = System.getProperty(WORKSPACE_DIR_PROP);
        if (sysProp != null && !sysProp.isEmpty()) {
            return normalize(sysProp);
        }

        // Priority 3: DIATOM_WORKSPACE_DIR environment variable
        String envVar = System.getenv("DIATOM_WORKSPACE_DIR");
        if (envVar != null && !envVar.isEmpty()) {
            return normalize(envVar);
        }

        // Priority 4: workspace.dir from config file
        String defaultDir = System.getProperty("diatom.original.user.dir",
                System.getProperty("user.dir"));
        String configVal = config.getProperty("workspace.dir", "");
        if (!configVal.isEmpty() && !configVal.equals("${user.dir}")) {
            if (configVal.contains("${user.dir}")) {
                configVal = configVal.replace("${user.dir}", defaultDir);
            }
            return normalize(configVal);
        }

        // Priority 5: default - original user dir / current user dir
        return normalize(defaultDir);
    }

    /**
     * Parse {@code --workspace-dir} / {@code -w} from CLI arguments and store
     * the value as the {@code diatom.ws.cli.arg} system property.
     * <p>
     * Call this early in {@code main()} before AppConfig construction.
     *
     * @param args the CLI arguments array (may be null)
     * @return the parsed value, or {@code null} if not specified
     */
    public static String parseCliArg(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--workspace-dir".equals(arg) || "-w".equals(arg)) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    String val = args[i + 1].trim();
                    if (!val.isEmpty()) {
                        System.setProperty(CLI_ARG_PROP, val);
                        return val;
                    }
                }
            }
            if (arg.startsWith("--workspace-dir=")) {
                String val = arg.substring("--workspace-dir=".length()).trim();
                if (!val.isEmpty()) {
                    System.setProperty(CLI_ARG_PROP, val);
                    return val;
                }
            }
        }
        return null;
    }

    /**
     * Return a human-readable label describing which source resolved the workspace
     * directory. Useful for log output.
     *
     * @param config AppConfig instance for fallback source check
     * @return source description (e.g. "CLI arg", "system property", "env var", "config", "default")
     */
    public static String getSourceLabel(AppConfig config) {
        if (System.getProperty(CLI_ARG_PROP) != null) {
            return "CLI arg";
        }
        if (System.getProperty(WORKSPACE_DIR_PROP) != null) {
            return "system property";
        }
        if (System.getenv("DIATOM_WORKSPACE_DIR") != null) {
            return "env var";
        }
        String configVal = config.getProperty("workspace.dir", "");
        if (!configVal.isEmpty()) {
            return "config";
        }
        return "default";
    }

    /**
     * Normalize a directory path to an absolute, normalized form.
     *
     * @param dir the directory path string
     * @return absolute normalized path string
     */
    public static String normalize(String dir) {
        if (dir == null || dir.isEmpty()) {
            return normalize(System.getProperty("user.dir"));
        }
        Path path = Paths.get(dir).toAbsolutePath().normalize();
        return path.toString();
    }

    private WorkspaceDirResolver() {
        // utility class
    }
}
