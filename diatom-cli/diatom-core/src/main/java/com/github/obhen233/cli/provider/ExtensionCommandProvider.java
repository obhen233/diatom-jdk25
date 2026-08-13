package com.github.obhen233.cli.provider;

import com.github.obhen233.core.database.SourceCodeExtensionsDao;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;

import java.util.List;

/**
 * Extension command provider for source code extension management.
 */
public class ExtensionCommandProvider implements CoreCommandProvider {

    private SourceCodeExtensionsDao sourceCodeExtensionsDao;

    public void setSourceCodeExtensionsDao(SourceCodeExtensionsDao sourceCodeExtensionsDao) {
        this.sourceCodeExtensionsDao = sourceCodeExtensionsDao;
    }

    @Override
    public String getCommandName() {
        return "extension";
    }

    @Override
    public String getDescription() {
        return "{{cli.extension.description}}";
    }

    @Override
    public String getHelp() {
        return "{{cli.extension.help}}";
    }

    @Override
    public String execute(String args, CommandOutput output) {
        if (sourceCodeExtensionsDao == null) {
            return "ERROR {{extension.database_not_initialized}}";
        }

        String trimmed = args.trim();
        if (trimmed.isEmpty()) {
            // No args at all: show list
            return executeList();
        }

        String[] parts = trimmed.split("\\s+");
        String subCmd = parts[0].toLowerCase();

        switch (subCmd) {
            case "":
            case "list": {
                return executeList();
            }
            case "add": {
                if (parts.length < 2) {
                    return "INFO {{extension.usage}}";
                }
                String extension = parts[1];
                if (!extension.startsWith(".")) {
                    extension = "." + extension;
                }
                if (sourceCodeExtensionsDao.exists(extension)) {
                    return "INFO {{extension.add.exists:" + extension + "}}";
                }
                sourceCodeExtensionsDao.insert(extension, "manual");
                return "SUCCESS {{extension.add.success:" + extension + "}}";
            }
            case "remove":
            case "delete": {
                if (parts.length < 2) {
                    return "INFO {{extension.usage}}";
                }
                String extension = parts[1];
                if (!extension.startsWith(".")) {
                    extension = "." + extension;
                }
                if (!sourceCodeExtensionsDao.exists(extension)) {
                    return "INFO {{extension.remove.not_found:" + extension + "}}";
                }
                sourceCodeExtensionsDao.delete(extension);
                return "SUCCESS {{extension.remove.success:" + extension + "}}";
            }
            case "enable": {
                if (parts.length < 2) {
                    return "INFO {{extension.usage}}";
                }
                try {
                    long id = Long.parseLong(parts[1]);
                    sourceCodeExtensionsDao.updateEnabled(id, true);
                    return "SUCCESS {{extension.enable.success:" + parts[1] + "}}";
                } catch (NumberFormatException e) {
                    return "ERROR {{extension.error.id_invalid:" + parts[1] + "}}";
                }
            }
            case "disable": {
                if (parts.length < 2) {
                    return "INFO {{extension.usage}}";
                }
                try {
                    long id = Long.parseLong(parts[1]);
                    sourceCodeExtensionsDao.updateEnabled(id, false);
                    return "SUCCESS {{extension.disable.success:" + parts[1] + "}}";
                } catch (NumberFormatException e) {
                    return "ERROR {{extension.error.id_invalid:" + parts[1] + "}}";
                }
            }
            case "reset": {
                sourceCodeExtensionsDao.deleteNonBuiltin();
                String[] builtinExtensions = getBuiltinExtensions();
                int inserted = 0;
                for (String ext : builtinExtensions) {
                    if (sourceCodeExtensionsDao.insertIfNotExists(ext, "built-in")) {
                        inserted++;
                    }
                }
                return "SUCCESS {{extension.reset.success:" + builtinExtensions.length + "}}";
            }
            default: {
                return "INFO {{extension.usage.detail}}";
            }
        }
    }

    private String executeList() {
        List<SourceCodeExtensionsDao.SourceCodeExtension> extensions = sourceCodeExtensionsDao.findAll();
        if (extensions.isEmpty()) {
            return "INFO {{extension.list.empty}}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("INFO {{extension.list.header}}");
        for (SourceCodeExtensionsDao.SourceCodeExtension ext : extensions) {
            sb.append("\n  [").append(ext.id).append("] ")
              .append(ext.extension)
              .append(ext.enabled ? "" : " [disabled]")
              .append(" (").append(ext.source).append(")");
        }
        return sb.toString();
    }

    private String[] getBuiltinExtensions() {
        return new String[]{
            ".java", ".kt", ".scala", ".py", ".js", ".ts", ".tsx", ".jsx", ".go", ".rs",
            ".c", ".cpp", ".cc", ".cxx", ".h", ".hpp", ".cs", ".rb", ".php", ".swift",
            ".vue", ".svelte", ".groovy", ".clj", ".ex", ".exs", ".erl", ".hs", ".ml",
            ".jsp", ".html", ".htm", ".xml", ".properties", ".tld", ".css", ".json",
            ".yaml", ".yml"
        };
    }
}
