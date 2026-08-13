package com.github.obhen233.cli.provider;

import com.github.obhen233.core.database.CommandRulesDao;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;

import java.util.*;

/**
 * Rules command provider for command rules management.
 */
public class RulesCommandProvider implements CoreCommandProvider {

    private CommandRulesDao commandRulesDao;

    public void setCommandRulesDao(CommandRulesDao commandRulesDao) {
        this.commandRulesDao = commandRulesDao;
    }

    @Override
    public String getCommandName() {
        return "rules";
    }

    @Override
    public String getDescription() {
        return "{{cli.rules.description}}";
    }

    @Override
    public String getHelp() {
        return "{{cli.rules.help}}";
    }

    @Override
    public String execute(String args, CommandOutput output) {
        if (commandRulesDao == null) {
            return "ERROR {{rules.database_not_initialized}}";
        }

        String[] parts = args.trim().split("\\s+");
        String subCmd = parts.length > 0 ? parts[0].toLowerCase() : "list";

        // Check for --json flag in any position
        boolean jsonFormat = false;
        for (String p : parts) {
            if ("--json".equals(p)) {
                jsonFormat = true;
                break;
            }
        }

        switch (subCmd) {
            case "":
            case "list": {
                String filter = null;
                for (int i = 1; i < parts.length; i++) {
                    if (!"--json".equals(parts[i])) {
                        filter = parts[i];
                        break;
                    }
                }
                List<CommandRulesDao.CommandRule> rules;

                if (filter == null || filter.isEmpty()) {
                    rules = commandRulesDao.findAll();
                } else if ("terminal".equals(filter) || "agent".equals(filter)) {
                    rules = commandRulesDao.findByMode(filter);
                } else if ("allowed".equals(filter) || "blocked".equals(filter) || "dangerous".equals(filter)) {
                    rules = commandRulesDao.findByType(filter);
                } else if ("built-in".equals(filter) || "manual".equals(filter) || "auto-learned".equals(filter)) {
                    rules = commandRulesDao.findBySource(filter);
                } else {
                    rules = commandRulesDao.findAll();
                }

                if (rules.isEmpty()) {
                    return jsonFormat ? "[]" : "INFO {{rules.list.empty}}";
                }

                if (jsonFormat) {
                    StringBuilder json = new StringBuilder("[");
                    boolean first = true;
                    for (CommandRulesDao.CommandRule rule : rules) {
                        if (!first) json.append(",");
                        first = false;
                        json.append("{\"id\":").append(rule.id)
                            .append(",\"mode\":\"").append(rule.mode)
                            .append("\",\"type\":\"").append(rule.type)
                            .append("\",\"pattern\":\"").append(rule.pattern.replace("\\", "\\\\").replace("\"", "\\\""))
                            .append("\",\"source\":\"").append(rule.source)
                            .append("\",\"enabled\":").append(rule.enabled)
                            .append(",\"createdAt\":").append(rule.createdAt)
                            .append(",\"updatedAt\":").append(rule.updatedAt)
                            .append("}");
                    }
                    json.append("]");
                    return json.toString();
                }

                // Group by mode (text format)
                Map<String, Map<String, List<String>>> grouped = new LinkedHashMap<>();
                for (CommandRulesDao.CommandRule rule : rules) {
                    grouped.computeIfAbsent(rule.mode, k -> new LinkedHashMap<>())
                          .computeIfAbsent(rule.type, k -> new ArrayList<>())
                          .add(rule.pattern + (rule.enabled ? "" : " [disabled]"));
                }

                StringBuilder sb = new StringBuilder();
                sb.append("INFO {{rules.list.header}}");
                for (Map.Entry<String, Map<String, List<String>>> modeEntry : grouped.entrySet()) {
                    sb.append("\n[").append(modeEntry.getKey()).append("]");
                    for (Map.Entry<String, List<String>> typeEntry : modeEntry.getValue().entrySet()) {
                        sb.append("\n  ").append(typeEntry.getKey()).append(" = ");
                        sb.append(String.join(", ", typeEntry.getValue()));
                    }
                    sb.append("\n");
                }
                return sb.toString();
            }
            case "add": {
                if (parts.length < 4) {
                    return "INFO {{rules.usage}}\nINFO {{rules.usage.mode:terminal, agent}}\nINFO {{rules.usage.type:allowed, blocked, dangerous}}";
                }
                String mode = parts[1];
                String type = parts[2];
                String pattern = args.substring(args.indexOf(type) + type.length()).trim();
                if (!isValidMode(mode)) {
                    return "ERROR {{rules.error.invalid_mode}}";
                }
                if (!isValidType(type)) {
                    return "ERROR {{rules.error.invalid_type}}";
                }
                if (pattern == null || pattern.isEmpty()) {
                    return "ERROR {{rules.error.pattern_empty}}";
                }
                CommandRulesDao.CommandRule rule = new CommandRulesDao.CommandRule(mode, type, pattern, "manual");
                commandRulesDao.insert(rule);
                return "SUCCESS {{rules.add.success:" . concat(mode).concat(":").concat(type).concat(":").concat(pattern).concat("}}");
            }
            case "remove":
            case "delete": {
                if (parts.length < 4) {
                    return "INFO {{rules.usage}}";
                }
                String mode = parts[1];
                String type = parts[2];
                String pattern = args.substring(args.indexOf(type) + type.length()).trim();
                if (!isValidMode(mode)) {
                    return "ERROR {{rules.error.invalid_mode}}";
                }
                if (!isValidType(type)) {
                    return "ERROR {{rules.error.invalid_type}}";
                }
                commandRulesDao.delete(mode, type, pattern);
                return "SUCCESS {{rules.remove.success:" . concat(mode).concat(":").concat(type).concat(":").concat(pattern).concat("}}");
            }
            case "enable": {
                if (parts.length < 2) {
                    return "INFO {{rules.usage}}";
                }
                try {
                    long id = Long.parseLong(parts[1]);
                    commandRulesDao.updateEnabled(id, true);
                    return "SUCCESS {{rules.enable.success:" . concat(String.valueOf(id)).concat("}}");
                } catch (NumberFormatException e) {
                    return "ERROR {{rules.error.id_invalid:" . concat(parts[1]).concat("}}");
                }
            }
            case "disable": {
                if (parts.length < 2) {
                    return "INFO {{rules.usage}}";
                }
                try {
                    long id = Long.parseLong(parts[1]);
                    commandRulesDao.updateEnabled(id, false);
                    return "SUCCESS {{rules.disable.success:" . concat(String.valueOf(id)).concat("}}");
                } catch (NumberFormatException e) {
                    return "ERROR {{rules.error.id_invalid:" . concat(parts[1]).concat("}}");
                }
            }
            case "clear": {
                if (parts.length < 2) {
                    return "INFO {{rules.usage}}\nINFO {{rules.usage.source:auto-learned, manual}}";
                }
                String source = parts[1];
                if (!"auto-learned".equals(source) && !"manual".equals(source)) {
                    return "ERROR {{rules.error.invalid_source}}";
                }
                int count = commandRulesDao.deleteBySource(source);
                return "SUCCESS {{rules.clear.success:" . concat(source).concat(":").concat(String.valueOf(count)).concat("}}");
            }
            case "reset": {
                int count = commandRulesDao.deleteNonBuiltin();
                List<CommandRulesDao.CommandRule> builtinRules = getBuiltinRules();
                for (CommandRulesDao.CommandRule rule : builtinRules) {
                    commandRulesDao.insertIfNotExists(rule);
                }
                return "SUCCESS {{rules.reset.success:" . concat(String.valueOf(builtinRules.size())).concat("}}");
            }
            default: {
                return "INFO {{rules.usage.detail}}";
            }
        }
    }

    private boolean isValidMode(String mode) {
        return "terminal".equals(mode) || "agent".equals(mode);
    }

    private boolean isValidType(String type) {
        return "allowed".equals(type) || "blocked".equals(type) || "dangerous".equals(type);
    }

    public static List<CommandRulesDao.CommandRule> getBuiltinRules() {
        List<CommandRulesDao.CommandRule> rules = new ArrayList<>();

        // Agent mode rules
        for (String p : Arrays.asList("rm -rf /", "format", "fdisk", "mkfs", "dd if="))
            rules.add(new CommandRulesDao.CommandRule("agent", "blocked", p, "built-in"));
        for (String p : Arrays.asList("rm -rf", "del /s"))
            rules.add(new CommandRulesDao.CommandRule("agent", "dangerous", p, "built-in"));
        for (String p : Arrays.asList("mvn", "git", "java", "javac", "npm", "node", "go", "python", "python3"))
            rules.add(new CommandRulesDao.CommandRule("agent", "allowed", p, "built-in"));

        // Terminal mode rules
        for (String p : Arrays.asList("rm -rf /", "format", "fdisk", "mkfs"))
            rules.add(new CommandRulesDao.CommandRule("terminal", "blocked", p, "built-in"));
        for (String p : Arrays.asList("&&", "||", ";", "$|", "`"))
            rules.add(new CommandRulesDao.CommandRule("terminal", "dangerous", p, "built-in"));
        for (String p : Arrays.asList("ls", "dir", "cat", "echo", "pwd", "cd", "git", "mvn", "npm", "node", "python", "curl", "clear"))
            rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", p, "built-in"));

        return rules;
    }
}
