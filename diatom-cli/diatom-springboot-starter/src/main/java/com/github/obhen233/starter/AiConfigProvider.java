package com.github.obhen233.starter;

import java.util.Map;

/**
 * SPI for providing AI configuration dynamically.
 *
 * When a Spring Boot project (e.g., diatom-ide) implements this interface
 * and registers it as a bean, the starter auto-configuration uses it to
 * configure diatom-core's AI client and agent at runtime — instead of
 * reading from static application.properties.
 *
 * This enables dynamic AI config (e.g., from a database, user settings UI)
 * to flow seamlessly into the core engine without modifying the starter.
 */
public interface AiConfigProvider {

    /**
     * The API base URL for the AI provider.
     * e.g. "https://api.openai.com/v1/chat/completions"
     */
    String getApiUrl();

    /**
     * The API authentication token or key.
     */
    String getApiToken();

    /**
     * The model name to use.
     * e.g. "gpt-4", "deepseek-chat", "MiniMax-M2.7-highspeed"
     */
    String getModel();

    /**
     * The API format.
     * <ul>
     *   <li>{@code auto} — detect from model name / endpoint (default)</li>
     *   <li>{@code openai} — OpenAI-compatible (Bearer auth)</li>
     *   <li>{@code anthropic} — Anthropic-style (x-api-key auth)</li>
     * </ul>
     */
    default String getFormat() {
        return "auto";
    }

    /**
     * Optional endpoint path override (e.g. {@code /v1/chat/completions}).
     * When set, the full API URL becomes {@code getApiUrl() + getEndpoint()}.
     * When empty, the endpoint is auto-resolved from the model name.
     */
    default String getEndpoint() {
        return "";
    }

    /**
     * Get a configuration value by key.
     * This allows diatom-core to query any configuration value from the IDE,
     * including system configs like "api.streaming".
     *
     * @param key the configuration key (e.g., "api.streaming", "aiApiUrl")
     * @return the configuration value, or null if not found
     */
    default String getConfig(String key) {
        return null;
    }

    /**
     * Get all configuration key-value pairs.
     * This allows diatom-core to get all settings at once for efficiency.
     *
     * @return a map of all configuration key-value pairs
     */
    default Map<String, String> getAllConfigs() {
        return new java.util.HashMap<>();
    }

    /**
     * Execute a core command line and return the output.
     * This allows IDE to execute core commands like "config set key value",
     * "tasks", "snapshot", etc.
     *
     * @param commandLine the full command line to execute
     * @return the command output, or null if the command is not recognized
     */
    default String executeCoreCommand(String commandLine) {
        return null;
    }

    /**
     * Get core help text.
     *
     * @param lang language code ("en" or "zh")
     * @return the core help text
     */
    default String getCoreHelp(String lang) {
        return null;
    }
}
