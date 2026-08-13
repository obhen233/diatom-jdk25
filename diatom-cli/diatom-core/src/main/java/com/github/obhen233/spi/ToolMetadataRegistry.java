package com.github.obhen233.spi;

import java.util.ServiceLoader;

/**
 * Registry for tool metadata.
 * Provides centralized access to tool names, descriptions, and confirmation messages.
 *
 * Implementations can be loaded via ServiceLoader (SPI mechanism).
 */
public interface ToolMetadataRegistry {

    /**
     * Register a tool's metadata.
     * @param metadata the tool metadata to register
     */
    void register(ToolMetadata metadata);

    /**
     * Get metadata for a tool.
     * @param toolName the tool name
     * @return the metadata, or null if not found
     */
    ToolMetadata get(String toolName);

    /**
     * Check if a tool is known (has metadata registered).
     * @param toolName the tool name
     * @return true if the tool has registered metadata
     */
    boolean isKnown(String toolName);

    /**
     * Get the human-readable name for a tool.
     * @param toolName the tool name
     * @param lang language code
     * @return the readable name, or the original toolName if not found
     */
    String getReadableName(String toolName, String lang);

    /**
     * Get the confirmation message for a tool with arguments.
     * @param toolName the tool name
     * @param argsJson the tool arguments as JSON
     * @param lang language code
     * @return the confirmation message, or null if not found
     */
    String getConfirmationMessage(String toolName, String argsJson, String lang);

    /**
     * Get the global registry instance.
     * Uses ServiceLoader to load implementations.
     * @return the global registry instance
     */
    static ToolMetadataRegistry getInstance() {
        ServiceLoader.load(ToolMetadataRegistry.class).forEach(r -> {
            // Just trigger ServiceLoader to load
        });
        return DefaultToolMetadataRegistry.getInstance();
    }

    /**
     * Default implementation of ToolMetadataRegistry.
     * Provides built-in tool metadata and supports SPI extension.
     */
    class DefaultToolMetadataRegistry implements ToolMetadataRegistry {
        private static volatile ToolMetadataRegistry instance;
        private final java.util.Map<String, ToolMetadata> registry = new java.util.concurrent.ConcurrentHashMap<>();

        public static ToolMetadataRegistry getInstance() {
            if (instance == null) {
                synchronized (DefaultToolMetadataRegistry.class) {
                    if (instance == null) {
                        instance = new DefaultToolMetadataRegistry();
                    }
                }
            }
            return instance;
        }

        public DefaultToolMetadataRegistry() {
            registerBuiltInTools();
        }

        private void registerBuiltInTools() {
            // Register built-in tool metadata
            register(new BuiltInToolMetadata("read_file"));
            register(new BuiltInToolMetadata("write_file"));
            register(new BuiltInToolMetadata("delete_file"));
            register(new BuiltInToolMetadata("create_directory"));
            register(new BuiltInToolMetadata("list_directory"));
            register(new BuiltInToolMetadata("search_files"));
            register(new BuiltInToolMetadata("run_command"));
            register(new BuiltInToolMetadata("compile_sources"));
            register(new BuiltInToolMetadata("replace_in_file"));
        }

        @Override
        public void register(ToolMetadata metadata) {
            if (metadata != null && metadata.getToolName() != null) {
                registry.put(metadata.getToolName(), metadata);
            }
        }

        @Override
        public ToolMetadata get(String toolName) {
            return registry.get(toolName);
        }

        @Override
        public boolean isKnown(String toolName) {
            return registry.containsKey(toolName);
        }

        @Override
        public String getReadableName(String toolName, String lang) {
            ToolMetadata meta = registry.get(toolName);
            if (meta != null) {
                String name = meta.getReadableName(lang);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
            return toolName;
        }

        @Override
        public String getConfirmationMessage(String toolName, String argsJson, String lang) {
            ToolMetadata meta = registry.get(toolName);
            if (meta != null) {
                return meta.getConfirmationMessage(argsJson, lang);
            }
            return null;
        }

        /**
         * Built-in tool metadata with i18n support.
         */
        private static class BuiltInToolMetadata implements ToolMetadata {
            private final String toolName;

            BuiltInToolMetadata(String toolName) {
                this.toolName = toolName;
            }

            @Override
            public String getToolName() {
                return toolName;
            }

            @Override
            public String getReadableName(String lang) {
                if ("zh".equals(lang)) {
                    switch (toolName) {
                        case "read_file": return "\u8BFB\u53D6\u6587\u4EF6";
                        case "write_file": return "\u5199\u5165\u6587\u4EF6";
                        case "delete_file": return "\u5220\u9664\u6587\u4EF6";
                        case "create_directory": return "\u521B\u5EFA\u76EE\u5F55";
                        case "list_directory": return "\u5217\u51FA\u76EE\u5F55";
                        case "search_files": return "\u641C\u7D22\u6587\u4EF6";
                        case "run_command": return "\u6267\u884C\u547D\u4EE4";
                        case "compile_sources": return "\u7F16\u8BD1\u6E90\u7801";
                        case "replace_in_file": return "\u66FF\u6362\u6587\u4EF6\u5185\u5BB9";
                        default: return toolName;
                    }
                } else {
                    switch (toolName) {
                        case "read_file": return "Read File";
                        case "write_file": return "Write File";
                        case "delete_file": return "Delete File";
                        case "create_directory": return "Create Directory";
                        case "list_directory": return "List Directory";
                        case "search_files": return "Search Files";
                        case "run_command": return "Run Command";
                        case "compile_sources": return "Compile Sources";
                        case "replace_in_file": return "Replace File Content";
                        default: return toolName;
                    }
                }
            }

            @Override
            public String getDescription(String lang) {
                return getReadableName(lang);
            }

            @Override
            public String getRiskLevel() {
                switch (toolName) {
                    case "write_file":
                    case "replace_in_file":
                        return "medium";
                    case "delete_file":
                    case "create_directory":
                        return "medium";
                    case "run_command":
                        return "high";
                    case "compile_sources":
                        return "medium";
                    default:
                        return "low";
                }
            }

            @Override
            public String getConfirmationMessage(String argsJson, String lang) {
                String path = extractPath(argsJson);
                if ("zh".equals(lang)) {
                    switch (toolName) {
                        case "read_file": return "\u5373\u5C06\u8BFB\u53D6\u6587\u4EF6: " + path;
                        case "write_file": return "\u5373\u5C06\u5199\u5165\u6587\u4EF6: " + path;
                        case "delete_file": return "\u5373\u5C06\u5220\u9664\u6587\u4EF6: " + path;
                        case "create_directory": return "\u5373\u5C06\u521B\u5EFA\u76EE\u5F55: " + path;
                        case "replace_in_file": return "\u5373\u5C06\u66FF\u6362\u6587\u4EF6\u5185\u5BB9: " + path;
                        case "run_command": return "\u5373\u5C06\u6267\u884C\u547D\u4EE4: " + extractCmd(argsJson);
                        case "compile_sources": return "\u5373\u5C06\u7F16\u8BD1\u6E90\u7801\u5E76\u66F4\u65B0\u6838\u5FC3";
                        default: return "\u5373\u5C06\u6267\u884C\u64CD\u4F5C: " + toolName;
                    }
                } else {
                    switch (toolName) {
                        case "read_file": return "About to read file: " + path;
                        case "write_file": return "About to write file: " + path;
                        case "delete_file": return "About to delete file: " + path;
                        case "create_directory": return "About to create directory: " + path;
                        case "replace_in_file": return "About to replace file content: " + path;
                        case "run_command": return "About to execute command: " + extractCmd(argsJson);
                        case "compile_sources": return "About to compile sources and update core";
                        default: return "About to execute: " + toolName;
                    }
                }
            }

            private String extractPath(String argsJson) {
                if (argsJson == null) return "";
                String[] keys = {"\"path\"", "\"filePath\"", "\"target\""};
                for (String key : keys) {
                    int pathIdx = argsJson.indexOf(key);
                    if (pathIdx >= 0) {
                        int colonIdx = argsJson.indexOf(":", pathIdx);
                        if (colonIdx >= 0) {
                            int startQuote = argsJson.indexOf("\"", colonIdx);
                            int endQuote = argsJson.indexOf("\"", startQuote + 1);
                            if (startQuote >= 0 && endQuote >= 0) {
                                return argsJson.substring(startQuote + 1, endQuote);
                            }
                        }
                    }
                }
                return "";
            }

            private String extractCmd(String argsJson) {
                if (argsJson == null) return "";
                int cmdIdx = argsJson.indexOf("\"cmd\"");
                if (cmdIdx >= 0) {
                    int colonIdx = argsJson.indexOf(":", cmdIdx);
                    if (colonIdx >= 0) {
                        int startQuote = argsJson.indexOf("\"", colonIdx);
                        int endQuote = argsJson.indexOf("\"", startQuote + 1);
                        if (startQuote >= 0 && endQuote >= 0) {
                            return argsJson.substring(startQuote + 1, endQuote);
                        }
                    }
                }
                return "";
            }
        }
    }
}
