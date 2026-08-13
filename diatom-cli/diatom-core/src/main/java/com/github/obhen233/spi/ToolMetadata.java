package com.github.obhen233.spi;

/**
 * Interface for tool metadata.
 * Provides human-readable names, descriptions, and confirmation messages for tools.
 */
public interface ToolMetadata {

    /**
     * Get the original tool name.
     * @return the tool name (e.g., "read_file", "a_b_c")
     */
    String getToolName();

    /**
     * Get a human-readable name for the tool in the specified language.
     * @param lang language code (e.g., "en", "zh")
     * @return the readable name (e.g., "Read File", "读取文件")
     */
    String getReadableName(String lang);

    /**
     * Get a description for the tool in the specified language.
     * @param lang language code
     * @return the description
     */
    String getDescription(String lang);

    /**
     * Get the risk level for the tool.
     * @return risk level: none, low, medium, high, critical
     */
    String getRiskLevel();

    /**
     * Get the confirmation message with arguments formatted in.
     * @param argsJson the tool arguments as JSON
     * @param lang language code
     * @return the confirmation message
     */
    String getConfirmationMessage(String argsJson, String lang);
}
