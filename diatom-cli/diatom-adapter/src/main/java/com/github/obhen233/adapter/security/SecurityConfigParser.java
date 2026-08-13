package com.github.obhen233.adapter.security;

import com.github.obhen233.adapter.spi.ApprovalPolicy;
import com.github.obhen233.adapter.spi.SandboxLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * Parser for {@code -l}, {@code -a}, and {@code -m} CLI security options.
 *
 * <p>Supports 4-level priority (from highest to lowest):</p>
 * <ol>
 *   <li>CLI arguments ({@code -l}, {@code -a})</li>
 *   <li>{@code -m} preset (CLI only, not from env/config)</li>
 *   <li>Environment variables ({@code DIATOM_LEVEL}, {@code DIATOM_APPROVAL_POLICY})</li>
 *   <li>{@code application.properties} configuration</li>
 * </ol>
 */
public class SecurityConfigParser {

    private SandboxLevel level;
    private ApprovalPolicy approvalPolicy;

    /**
     * Parse security options with full 4-level priority resolution.
     *
     * @param cliLevel   value from {@code -l} CLI arg, null if not provided
     * @param cliPolicy  value from {@code -a} CLI arg, null if not provided
     * @param cliMode    value from {@code -m} CLI arg (preset), null if not provided
     */
    public SecurityConfigParser(String cliLevel, String cliPolicy, String cliMode) {
        this.level = resolveLevel(cliLevel, cliMode);
        this.approvalPolicy = resolvePolicy(cliPolicy, cliMode);
    }

    public SandboxLevel getLevel() { return level; }
    public ApprovalPolicy getApprovalPolicy() { return approvalPolicy; }

    // ---- preset mapping ----

    private static final Map<String, Preset> PRESETS = new HashMap<>();
    static {
        PRESETS.put("normal", new Preset(SandboxLevel.WORKSPACE, ApprovalPolicy.ASK));
        PRESETS.put("auto", new Preset(SandboxLevel.WORKSPACE, ApprovalPolicy.AUTO));
        PRESETS.put("silent", new Preset(SandboxLevel.WORKSPACE, ApprovalPolicy.SILENT));
        PRESETS.put("readonly", new Preset(SandboxLevel.READ_ONLY, ApprovalPolicy.SILENT));
        PRESETS.put("unrestricted", new Preset(SandboxLevel.FULL, ApprovalPolicy.AUTO));
    }

    private static class Preset {
        final SandboxLevel level;
        final ApprovalPolicy policy;
        Preset(SandboxLevel level, ApprovalPolicy policy) {
            this.level = level;
            this.policy = policy;
        }
    }

    // ---- resolution logic ----

    private SandboxLevel resolveLevel(String cliLevel, String cliMode) {
        // Priority 1: CLI -l
        if (cliLevel != null && !cliLevel.isEmpty()) {
            return parseLevel(cliLevel);
        }
        // Priority 2: -m preset
        if (cliMode != null && !cliMode.isEmpty()) {
            Preset preset = PRESETS.get(cliMode.toLowerCase());
            if (preset != null) return preset.level;
        }
        // Priority 3: Environment variable
        String envLevel = System.getenv("DIATOM_LEVEL");
        if (envLevel != null && !envLevel.isEmpty()) {
            return parseLevel(envLevel);
        }
        // Priority 4: default
        return SandboxLevel.WORKSPACE;
    }

    private ApprovalPolicy resolvePolicy(String cliPolicy, String cliMode) {
        // Priority 1: CLI -a
        if (cliPolicy != null && !cliPolicy.isEmpty()) {
            return parsePolicy(cliPolicy);
        }
        // Priority 2: -m preset
        if (cliMode != null && !cliMode.isEmpty()) {
            Preset preset = PRESETS.get(cliMode.toLowerCase());
            if (preset != null) return preset.policy;
        }
        // Priority 3: Environment variable
        String envPolicy = System.getenv("DIATOM_APPROVAL_POLICY");
        if (envPolicy != null && !envPolicy.isEmpty()) {
            return parsePolicy(envPolicy);
        }
        // Priority 4: default
        return ApprovalPolicy.SILENT;
    }

    private static SandboxLevel parseLevel(String s) {
        String lower = s.toLowerCase().replace('-', '_');
        return switch (lower) {
            case "read_only", "readonly", "ro" -> SandboxLevel.READ_ONLY;
            case "workspace", "ws" -> SandboxLevel.WORKSPACE;
            case "full", "f" -> SandboxLevel.FULL;
            default -> SandboxLevel.WORKSPACE;
        };
    }

    private static ApprovalPolicy parsePolicy(String s) {
        String lower = s.toLowerCase();
        return switch (lower) {
            case "ask", "a" -> ApprovalPolicy.ASK;
            case "auto", "au" -> ApprovalPolicy.AUTO;
            case "silent", "s" -> ApprovalPolicy.SILENT;
            default -> ApprovalPolicy.SILENT;
        };
    }
}
