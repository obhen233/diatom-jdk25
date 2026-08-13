package com.github.obhen233.core.gateway.http.dto;

import java.util.List;

public class RulesListResponse {
    public String status;
    public String target;
    public List<CommandRuleEntry> rules;

    public static class CommandRuleEntry {
        public long id;
        public String mode;
        public String type;
        public String pattern;
        public String source;
        public boolean enabled;
        public long createdAt;
        public long updatedAt;
    }
}
