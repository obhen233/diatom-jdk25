package com.github.obhen233.adapter.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Unified workspace directory resolver with the following priority chain:
 * <ol>
 *   <li>{@code --workspace-dir / -w} CLI flag (stored as {@code diatom.ws.cli.arg} system property)</li>
 *   <li>{@code -Dworkspace.dir} system property</li>
 *   <li>{@code DIATOM_WORKSPACE_DIR} environment variable</li>
 *   <li>{@code workspace.dir} config property (read from application.properties/yml)</li>
 *   <li>{@code diatom.original.user.dir} system property (preserved from original working dir)</li>
 *   <li>{@code user.dir} system property (secondary fallback)</li>
 * </ol>
 *
 * <p>Mirrors the core {@code com.github.obhen233.util.WorkspaceDirResolver} logic
 * but accepts a plain string for the config-file value rather than an AppConfig instance.</p>
 */
public class WorkspaceDirResolver {

    private static final String CLI_ARG_PROP = "diatom.ws.cli.arg";
    private static final String WORKSPACE_DIR_PROP = "workspace.dir";

    /**
     * Execute the full priority chain to resolve the workspace directory.
     *
     * @param configWorkspaceDir value of {@code workspace.dir} from config file, or {@code null} if absent
     * @return resolved, normalized absolute path
     */
    public static String resolve(String configWorkspaceDir) {
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
        if (configWorkspaceDir != null && !configWorkspaceDir.isEmpty()) {
            String resolved = configWorkspaceDir;
            String userDir = System.getProperty("user.dir", ".");
            if (resolved.contains("${user.dir}")) {
                resolved = resolved.replace("${user.dir}", userDir);
            }
            return normalize(resolved);
        }

        // Priority 5: diatom.original.user.dir (preserved from BootstrapAdapter)
        String originalUserDir = System.getProperty("diatom.original.user.dir");
        if (originalUserDir != null && !originalUserDir.isEmpty()) {
            return normalize(originalUserDir);
        }

        // Priority 6: default - user.dir
        return normalize(System.getProperty("user.dir", "."));
    }

    /**
     * Parse {@code --workspace-dir} / {@code -w} from CLI arguments and store
     * the value as the {@code diatom.ws.cli.arg} system property.
     * <p>
     * Call this early in {@code main()} before other configuration is loaded.
     *
     * @param args the CLI arguments array (may be null)
     * @return {@code true} if a workspace directory was found in CLI arguments
     */
    public static boolean parseCliArg(String[] args) {
        if (args == null) return false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--workspace-dir".equals(arg) || "-w".equals(arg)) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    String val = args[i + 1].trim();
                    if (!val.isEmpty()) {
                        System.setProperty(CLI_ARG_PROP, val);
                        return true;
                    }
                }
            }
            if (arg.startsWith("--workspace-dir=")) {
                String val = arg.substring("--workspace-dir=".length()).trim();
                if (!val.isEmpty()) {
                    System.setProperty(CLI_ARG_PROP, val);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Return a human-readable label describing which source resolved the workspace
     * directory. Useful for log output.
     *
     * @param configWorkspaceDir value of {@code workspace.dir} from config file, or {@code null} if absent
     * @return source description (e.g. "CLI arg", "system property", "env var", "config", "default")
     */
    public static String getSourceLabel(String configWorkspaceDir) {
        if (System.getProperty(CLI_ARG_PROP) != null) {
            return "CLI arg";
        }
        if (System.getProperty(WORKSPACE_DIR_PROP) != null) {
            return "system property";
        }
        if (System.getenv("DIATOM_WORKSPACE_DIR") != null) {
            return "env var";
        }
        if (configWorkspaceDir != null && !configWorkspaceDir.isEmpty()) {
            return "config";
        }
        return "default";
    }

    /**
     * Normalize a directory path to an absolute, normalized form.
     *
     * @param dir the directory path string
     * @return absolute normalized path string, or default (original user dir) if input is null/empty
     */
    public static String normalize(String dir) {
        if (dir == null || dir.isEmpty()) {
            String original = System.getProperty("diatom.original.user.dir");
            if (original != null && !original.isEmpty()) {
                return normalize(original);
            }
            return normalize(System.getProperty("user.dir", "."));
        }
        Path path = Paths.get(dir).toAbsolutePath().normalize();
        return path.toString();
    }

    private WorkspaceDirResolver() {
        // utility class
    }
}
