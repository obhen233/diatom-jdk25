package com.github.obhen233.core.mcp;

import com.github.obhen233.core.tool.Tool;

import java.util.Map;

/**
 * MCP Server interface for Model Context Protocol
 * Defines three core primitives: Tools, Resources, Prompts
 */
public interface McpServer {

    String getName();

    String getDescription();

    default Map<String, Tool> listTools() {
        return null;
    }

    default String callTool(String name, String args) {
        return null;
    }

    default Map<String, Resource> listResources() {
        return null;
    }

    default String readResource(String uri) {
        return null;
    }

    default Map<String, Prompt> listPrompts() {
        return null;
    }

    default PromptResult getPrompt(String name, Map<String, String> args) {
        return null;
    }

    class Resource {
        private String uri;
        private String name;
        private String mimeType;
        private String description;

        public Resource() {}

        public Resource(String uri, String name, String mimeType, String description) {
            this.uri = uri;
            this.name = name;
            this.mimeType = mimeType;
            this.description = description;
        }

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    class Prompt {
        private String name;
        private String description;
        private String argumentsSchema;

        public Prompt() {}

        public Prompt(String name, String description, String argumentsSchema) {
            this.name = name;
            this.description = description;
            this.argumentsSchema = argumentsSchema;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getArgumentsSchema() { return argumentsSchema; }
        public void setArgumentsSchema(String argumentsSchema) { this.argumentsSchema = argumentsSchema; }
    }

    class PromptResult {
        private String messagesJson;

        public PromptResult() {}

        public PromptResult(String messagesJson) {
            this.messagesJson = messagesJson;
        }

        public String getMessagesJson() { return messagesJson; }
        public void setMessagesJson(String messagesJson) { this.messagesJson = messagesJson; }
    }
}