package com.github.obhen233.core.skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class Skill {
    private String name;
    private String description;
    private String version;
    private String allowedTools;
    private boolean enabled;
    private int priority;
    private List<String> triggers;
    private String body;
    private Path filePath;
    private String profile;
    private String kind = "user"; // "system" or "user" (default "user")
    private Map<String, Object> variables; // Variable definitions with defaults

    public Skill() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getAllowedTools() { return allowedTools; }
    public void setAllowedTools(String allowedTools) { this.allowedTools = allowedTools; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public List<String> getTriggers() { return triggers; }
    public void setTriggers(List<String> triggers) { this.triggers = triggers; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public Path getFilePath() { return filePath; }
    public void setFilePath(Path filePath) { this.filePath = filePath; }

    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public Map<String, Object> getVariables() { return variables; }
    public void setVariables(Map<String, Object> variables) { this.variables = variables; }

    public boolean matches(String query, List<String> filePaths) {
        if (!enabled) return false;

        // No triggers defined: only system-kind skills automatically match.
        // User skills without triggers should only activate via explicit matching.
        if (triggers == null || triggers.isEmpty()) {
            return "system".equals(kind);
        }

        // Check triggers
        for (String trigger : triggers) {
            if (trigger.startsWith("*.")) {
                String ext = trigger.substring(1);
                for (String file : filePaths) {
                    if (file.endsWith(ext)) return true;
                }
            } else if (query.contains(trigger)) {
                return true;
            }
        }
        return false;
    }
}
