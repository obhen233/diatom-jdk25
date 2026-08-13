package com.github.obhen233.spi;

/**
 * Customize terminal UI behavior.
 * Allows custom modules to override prompts, titles, or styling.
 */
public interface UiCustomizer {

    /**
     * Get a custom title for the terminal UI.
     * @return custom title, or null to use default
     */
    default String getTitle() { return null; }

    /**
     * Get a custom prompt prefix.
     * @return prompt prefix string, or null to use default
     */
    default String getPromptPrefix() { return null; }

    /**
     * Get a custom welcome message shown at startup.
     * @return welcome message, or null to use default
     */
    default String getWelcomeMessage() { return null; }
}
