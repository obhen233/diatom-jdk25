package com.github.obhen233.starter.ide;

/**
 * Exposes mode capabilities to the IDE so the IDE can stay agnostic
 * to the concrete diatom mode (standard/gateway/adapter/worker/api).
 *
 * <p>The IDE should NOT parse {@code diatom.mode} itself. Instead it injects
 * this bean and asks {@link #requiresManualAiConfig()} when it needs to decide
 * whether to show/maintain manual AI URL/key/model configuration fields.</p>
 */
public class IdeModeCapabilities {

    private final String mode;
    private final boolean requiresManualAiConfig;

    public IdeModeCapabilities(String mode, boolean requiresManualAiConfig) {
        this.mode = mode;
        this.requiresManualAiConfig = requiresManualAiConfig;
    }

    /**
     * Current diatom mode string. For display/logging only —
     * the IDE must NOT interpret specific mode values.
     */
    public String getMode() {
        return mode;
    }

    /**
     * Whether the IDE needs to maintain manual AI URL/key/model config.
     * <ul>
     *   <li>standard / gateway / gateway:* / worker → {@code true}</li>
     *   <li>api / adapter → {@code false} (AI handled by remote Gateway or external agent)</li>
     * </ul>
     */
    public boolean requiresManualAiConfig() {
        return requiresManualAiConfig;
    }
}
