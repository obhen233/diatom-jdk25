package com.github.obhen233.core.command.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.SystemConfigDao;
import com.github.obhen233.util.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.obhen233.util.JsonUtils;

/**
 * Config Tools - System configuration management tools
 *
 * Provides commands for viewing and modifying system configuration.
 * Configuration changes made via config set are effective for the current session
 * and will be overridden by properties file on restart.
 *
 * NOTE: This class is NOT exposed to AI model via @ToolMethod.
 * It's a user command tool managed through CoreCommandProvider SPI.
 */
public class ConfigTools {
    private static final Logger logger = LoggerFactory.getLogger(ConfigTools.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();

    private final ConfigManager configManager;
    private final DatabaseManager dbManager;
    private final Set<String> allowedCommands;

    public ConfigTools(ConfigManager configManager, DatabaseManager dbManager) {
        this.configManager = configManager;
        this.dbManager = dbManager;
        this.allowedCommands = loadAllowedCommands();
    }

    /**
     * Load allowed commands from command-whitelist.json
     */
    private Set<String> loadAllowedCommands() {
        Set<String> commands = new HashSet<>();
        try (InputStream is = getClass().getResourceAsStream("/command-whitelist.json")) {
            if (is != null) {
                Map<String, Object> data = mapper.readValue(is, Map.class);
                List<Map<String, Object>> cmds = (List<Map<String, Object>>) data.get("commands");
                if (cmds != null) {
                    for (Map<String, Object> cmd : cmds) {
                        commands.add((String) cmd.get("command"));
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load command-whitelist.json: {}", e.getMessage());
        }
        return commands;
    }

    public String configList(String argsJson) {
        try {
            String category = null;
            if (argsJson != null && !argsJson.trim().isEmpty()) {
                argsJson = argsJson.trim();
                if (argsJson.startsWith("{")) {
                    Map<String, String> args = parseArgs(argsJson);
                    category = args.get("category");
                } else {
                    category = argsJson.replace("\"", "").trim();
                    if (category.isEmpty()) {
                        category = null;
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            List<SystemConfigDao.SystemConfig> configs;

            if (category != null && !category.isEmpty()) {
                configs = configManager.getByCategory(category);
                sb.append("{{config.list.category.header:").append(configManager.getCategoryDisplayName(category)).append("}}");
            } else {
                configs = configManager.getAll();
                sb.append("{{config.list.header:").append(configs.size()).append("}}");
            }

            sb.append("\n\n");

            String currentCategory = null;
            for (SystemConfigDao.SystemConfig config : configs) {
                if (category == null && !config.category.equals(currentCategory)) {
                    currentCategory = config.category;
                    sb.append("[").append(configManager.getCategoryDisplayName(currentCategory)).append("]\n");
                }

                String effectiveValue = getEffectiveValue(config);
                sb.append("  ").append(config.configKey)
                  .append(" = ")
                  .append(effectiveValue != null ? effectiveValue : "{{config.empty}}")
                  .append("\n");

                String constraint = getConstraintDescription(config);
                if (constraint != null) {
                    sb.append("    {{config.type}}: ").append(constraint).append("\n");
                }
            }

            return sb.toString();

        } catch (Exception e) {
            logger.error("Error listing configs", e);
            return "Error: " + e.getMessage();
        }
    }

    public String configGet(String argsJson) {
        try {
            String key = extractKey(argsJson);
            if (key == null || key.isEmpty()) {
                return "Error: {{config.error.key_required:key is required}}";
            }

            SystemConfigDao.SystemConfig config = configManager.getConfig(key);
            if (config == null) {
                return "Error: {{config.error.key_not_found:" + key + "}}";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(config.configKey).append(" = ").append(getEffectiveValue(config)).append("\n\n");

            String constraint = getConstraintDescription(config);
            if (constraint != null) {
                sb.append("{{config.type}}: ").append(constraint).append("\n");
            }

            if (config.i18nKey != null && !config.i18nKey.isEmpty()) {
                sb.append("{{config.label}}: {{").append(config.i18nKey).append("}}\n");
            }

            String source = getValueSource(key);
            if (!"properties".equals(source)) {
                sb.append("{{config.source}}: ").append(source).append("\n");
            }

            return I18n.resolveTemplate(sb.toString());

        } catch (Exception e) {
            logger.error("Error getting config", e);
            return "Error: " + e.getMessage();
        }
    }

    public String configSet(String argsJson) {
        try {
            Map<String, String> args = parseArgs(argsJson);
            String key = args.get("key");
            String value = args.get("value");

            if (key == null || key.isEmpty()) {
                return "Error: {{config.error.key_required:key is required}}";
            }
            if (value == null) {
                return "Error: {{config.error.value_required}}";
            }

            // 支持 gateway.url[N] 索引语法
            // 例如: config set gateway.url[1] http://127.0.0.1:8081
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "^(gateway\\.url)\\[(\\d+)\\]$").matcher(key);
            if (matcher.matches()) {
                String baseKey = matcher.group(1);
                int index = Integer.parseInt(matcher.group(2));
                return updateGatewayUrlIndex(baseKey, index, value);
            }

            ConfigManager.ValidationResult validation = configManager.validate(key, value);
            if (!validation.valid) {
                return "Error: " + validation.message;
            }

            String result = configManager.set(key, value);
            return result;

        } catch (Exception e) {
            logger.error("Error setting config", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 更新 gateway.url 指定索引的地址
     * 同时自动补齐 gateway.url 配置项的逗号分隔值
     */
    private String updateGatewayUrlIndex(String baseKey, int index, String newUrl) {
        // 获取当前的 gateway.url 值
        SystemConfigDao.SystemConfig current = configManager.getConfig(baseKey);
        String currentValue = (current != null) ? current.configValue : null;

        java.util.List<String> urls = new java.util.ArrayList<>();
        if (currentValue != null && !currentValue.isEmpty()) {
            for (String u : currentValue.split(",")) {
                u = u.trim();
                if (!u.isEmpty()) urls.add(u);
            }
        }

        // 确保列表长度足够
        while (urls.size() <= index) {
            urls.add("http://127.0.0.1:8080");
        }
        urls.set(index, newUrl);

        // 保存回 gateway.url（逗号分隔格式，GatewayAddressConfig 会自动读取）
        String newValue = String.join(",", urls);
        System.setProperty("gateway.url", newValue);
        configManager.set(baseKey, newValue);

        logger.info("Gateway URL index {} updated to: {}", index, newUrl);
        return "gateway.url[" + index + "] = " + newUrl;
    }

    public String configReset(String argsJson) {
        try {
            String key = extractKey(argsJson);
            if (key == null || key.isEmpty()) {
                return "Error: {{config.error.key_required:key is required}}";
            }

            String result = configManager.reset(key);
            return result;

        } catch (Exception e) {
            logger.error("Error resetting config", e);
            return "Error: " + e.getMessage();
        }
    }

    public String configExport(String argsJson) {
        try {
            String path = extractPath(argsJson);
            if (path == null || path.isEmpty()) {
                path = Paths.get(System.getProperty("user.home"), ".diatom", "application.properties").toString();
            }

            List<SystemConfigDao.SystemConfig> configs = configManager.getAll();

            StringBuilder content = new StringBuilder();
            content.append("# Diatom CLI Configuration\n");
            content.append("# Exported at: ").append(new java.util.Date()).append("\n\n");

            for (SystemConfigDao.SystemConfig config : configs) {
                String value = getEffectiveValue(config);
                if (value != null && !value.isEmpty()) {
                    content.append("# ").append(config.i18nKey != null ? config.i18nKey : config.configKey).append("\n");
                    content.append(config.configKey).append("=").append(value).append("\n\n");
                }
            }

            Files.write(Paths.get(path), content.toString().getBytes());

            return "{{config.export.success:" + path + "}}";

        } catch (Exception e) {
            logger.error("Error exporting config", e);
            return "Error: " + e.getMessage();
        }
    }

    public String configImport(String argsJson) {
        try {
            String path = extractPath(argsJson);
            if (path == null || path.isEmpty()) {
                return "Error: {{config.error.path_required}}";
            }

            Path configPath = Paths.get(path);
            if (!Files.exists(configPath)) {
                return "Error: File not found: " + path;
            }

            java.util.Properties props = new java.util.Properties();
            try (InputStream is = Files.newInputStream(configPath)) {
                props.load(is);
            }

            int imported = 0;
            int skipped = 0;

            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);

                ConfigManager.ValidationResult validation = configManager.validate(key, value);
                if (validation.valid) {
                    configManager.set(key, value);
                    imported++;
                } else {
                    skipped++;
                    logger.warn("Skipped invalid config: {} = {} ({})", key, value, validation.message);
                }
            }

            return String.format("Import completed: %d imported, %d skipped", imported, skipped);

        } catch (Exception e) {
            logger.error("Error importing config", e);
            return "Error: " + e.getMessage();
        }
    }

    public String configHelp(String argsJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Config 命令帮助 ===\n\n");
        sb.append("用法:\n");
        sb.append("  config list [category]     # 列出配置 (可选: api,workspace,agent,sandbox,logging,cleanup)\n");
        sb.append("  config get <key>          # 获取单个配置\n");
        sb.append("  config set <key> <value>  # 设置配置 (当前会话有效)\n");
        sb.append("  config reset <key>        # 重置为默认值\n");
        sb.append("  config export [path]      # 导出到 properties 文件\n");
        sb.append("  config import <path>      # 从 properties 文件导入\n");
        sb.append("  config help               # 显示帮助\n\n");
        sb.append("示例:\n");
        sb.append("  config list sandbox       # 列出沙箱配置\n");
        sb.append("  config get api.key        # 获取 API key\n");
        sb.append("  config set command.timeout 120  # 设置命令超时\n");
        sb.append("  config reset agent.max_steps  # 重置为默认值\n\n");
        sb.append("注意:\n");
        sb.append("  - config set 的修改当前会话有效\n");
        sb.append("  - 重启后以 properties 文件为准\n");
        return sb.toString();
    }

    public String listAllowedCommands(String argsJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Allowed Commands (").append(allowedCommands.size()).append(") ===\n\n");

        Map<String, Set<String>> byType = categorizeCommands();

        for (Map.Entry<String, Set<String>> entry : byType.entrySet()) {
            sb.append("[").append(entry.getKey()).append("]\n");
            for (String cmd : entry.getValue()) {
                sb.append("  ").append(cmd).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private Map<String, Set<String>> categorizeCommands() {
        Map<String, Set<String>> categorized = new java.util.LinkedHashMap<>();
        categorized.put("maven", new java.util.TreeSet<>());
        categorized.put("git", new java.util.TreeSet<>());
        categorized.put("npm", new java.util.TreeSet<>());
        categorized.put("java", new java.util.TreeSet<>());
        categorized.put("python", new java.util.TreeSet<>());
        categorized.put("shell", new java.util.TreeSet<>());
        categorized.put("other", new java.util.TreeSet<>());

        for (String cmd : allowedCommands) {
            String type = getCommandType(cmd);
            if (categorized.containsKey(type)) {
                categorized.get(type).add(cmd);
            } else {
                categorized.get("other").add(cmd);
            }
        }

        return categorized;
    }

    private String getCommandType(String cmd) {
        if (cmd.equals("mvn")) return "maven";
        if (cmd.equals("git")) return "git";
        if (cmd.equals("npm") || cmd.equals("npx")) return "npm";
        if (cmd.equals("java") || cmd.equals("javac") || cmd.equals("jar")) return "java";
        if (cmd.equals("python") || cmd.equals("python3")) return "python";
        if (cmd.equals("go")) return "go";
        if (cmd.equals("make") || cmd.equals("cmake") || cmd.equals("ant") || cmd.equals("gradle")) return "build";
        if (cmd.equals("curl") || cmd.equals("wget")) return "network";
        if (cmd.equals("docker") || cmd.equals("docker-compose")) return "docker";
        return "shell";
    }

    private String getEffectiveValue(SystemConfigDao.SystemConfig config) {
        String value = config.configValue;
        if (config.source != null && "runtime".equals(config.source)) {
            value = config.configValue;
        }
        if ("api.key".equals(config.configKey) && value != null) {
            if (value.length() > 7) {
                int maskedLength = value.length() - 7;
                String masked = new String(new char[maskedLength]).replace('\0', '*');
                value = value.substring(0, 3) + masked + value.substring(value.length() - 4);
            } else {
                value = new String(new char[value.length()]).replace('\0', '*');
            }
        }
        return value;
    }

    private String getValueSource(String key) {
        SystemConfigDao.SystemConfig config = configManager.getConfig(key);
        if (config != null && config.source != null) {
            return config.source;
        }
        return "unknown";
    }

    private String getConstraintDescription(SystemConfigDao.SystemConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append(config.configType);

        if ("enum".equals(config.configType) && config.allowedValues != null) {
            sb.append(" (").append(config.allowedValues).append(")");
        } else if ("int".equals(config.configType)) {
            if (config.minValue != null && config.maxValue != null) {
                sb.append(" (").append(config.minValue).append("-").append(config.maxValue).append(")");
            } else if (config.minValue != null) {
                sb.append(" (>=").append(config.minValue).append(")");
            } else if (config.maxValue != null) {
                sb.append(" (<=").append(config.maxValue).append(")");
            }
        } else if ("boolean".equals(config.configType)) {
            sb.append(" (true/false)");
        }

        return sb.toString();
    }

    private Map<String, String> parseArgs(String argsJson) {
        Map<String, String> result = new java.util.HashMap<>();
        if (argsJson == null || argsJson.trim().isEmpty()) {
            return result;
        }

        try {
            if (argsJson.trim().startsWith("{")) {
                Map<String, String> parsed = mapper.readValue(argsJson, Map.class);
                result.putAll(parsed);
                return result;
            }
        } catch (Exception e) {
            // Fall through to plain text parsing
        }

        String[] parts = argsJson.trim().split("\\s+", 2);
        if (parts.length >= 1) {
            result.put("key", parts[0].replace("\"", ""));
        }
        if (parts.length >= 2) {
            result.put("value", parts[1].replace("\"", ""));
        }
        return result;
    }

    private String extractKey(String argsJson) {
        if (argsJson == null || argsJson.trim().isEmpty()) {
            return null;
        }
        Map<String, String> args = parseArgs(argsJson);
        return args.get("key");
    }

    private String extractPath(String argsJson) {
        if (argsJson == null || argsJson.trim().isEmpty()) {
            return null;
        }
        try {
            Map<String, String> args = mapper.readValue(argsJson, Map.class);
            String path = args.get("path");
            if (path != null && !path.isEmpty()) {
                return path;
            }
        } catch (Exception e) {
            // Fall through
        }

        String trimmed = argsJson.trim().replace("\"", "");
        return trimmed.isEmpty() ? null : trimmed;
    }
}
