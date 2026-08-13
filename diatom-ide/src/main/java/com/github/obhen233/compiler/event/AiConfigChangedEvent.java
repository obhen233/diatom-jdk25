package com.github.obhen233.compiler.event;

import org.springframework.context.ApplicationEvent;

/**
 * Event published when AI configuration settings change.
 * Used to trigger cache invalidation in IdeAiConfigService.
 */
public class AiConfigChangedEvent extends ApplicationEvent {

    private final String settingKey;
    private final String newValue;

    public AiConfigChangedEvent(Object source, String settingKey, String newValue) {
        super(source);
        this.settingKey = settingKey;
        this.newValue = newValue;
    }

    public AiConfigChangedEvent(Object source) {
        super(source);
        this.settingKey = null;
        this.newValue = null;
    }

    public String getSettingKey() {
        return settingKey;
    }

    public String getNewValue() {
        return newValue;
    }
}
