package com.github.obhen233.spi;

import java.util.Collections;
import java.util.List;

/**
 * System configuration provider.
 * Allows custom modules to register configuration items in the system_config table.
 */
public interface SystemConfigProvider {

    /**
     * Provide configuration item definitions.
     * These are registered in the system_config table after database initialization.
     * @return list of configuration definitions
     */
    default List<ConfigDefinition> getConfigDefinitions() {
        return Collections.emptyList();
    }

    /**
     * Called when a configuration value changes.
     * @param key the configuration key
     * @param oldValue the previous value
     * @param newValue the new value
     */
    default void onConfigChanged(String key, String oldValue, String newValue) {}

    /**
     * Configuration item definition.
     */
    class ConfigDefinition {
        public String key;
        public String defaultValue;
        public String type;          // string, integer, boolean, enum
        public String category;     // grouping, e.g., "custom", "model", "ui"
        public String allowedValues; // comma-separated, for enum type
        public Integer minValue;
        public Integer maxValue;
        public String description;
        public String i18nKey;

        public ConfigDefinition() {}

        public static ConfigDefinition builder() {
            return new ConfigDefinition();
        }

        public ConfigDefinition key(String key) {
            this.key = key;
            return this;
        }

        public ConfigDefinition defaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public ConfigDefinition type(String type) {
            this.type = type;
            return this;
        }

        public ConfigDefinition category(String category) {
            this.category = category;
            return this;
        }

        public ConfigDefinition allowedValues(String allowedValues) {
            this.allowedValues = allowedValues;
            return this;
        }

        public ConfigDefinition minValue(Integer minValue) {
            this.minValue = minValue;
            return this;
        }

        public ConfigDefinition maxValue(Integer maxValue) {
            this.maxValue = maxValue;
            return this;
        }

        public ConfigDefinition description(String description) {
            this.description = description;
            return this;
        }

        public ConfigDefinition i18nKey(String i18nKey) {
            this.i18nKey = i18nKey;
            return this;
        }
    }
}
