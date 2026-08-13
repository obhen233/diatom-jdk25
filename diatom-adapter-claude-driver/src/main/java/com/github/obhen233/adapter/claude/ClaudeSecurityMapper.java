package com.github.obhen233.adapter.claude;

import com.github.obhen233.adapter.spi.ApprovalPolicy;
import com.github.obhen233.adapter.spi.SandboxLevel;
import com.github.obhen233.adapter.spi.SecurityMapper;

import java.util.Map;

/**
 * Security mapper for Claude Code CLI.
 *
 * <p>Maps diatom {@link SandboxLevel} and {@link ApprovalPolicy} to
 * Claude Code's {@code --permission-mode} flag and related options.</p>
 *
 * <table>
 *   <tr><th>SandboxLevel</th><th>ApprovalPolicy</th><th>permission-mode</th><th>Extra</th></tr>
 *   <tr><td>READ_ONLY</td><td>any</td><td>{@code plan}</td><td>—</td></tr>
 *   <tr><td>WORKSPACE</td><td>ASK</td><td>{@code default}</td><td>—</td></tr>
 *   <tr><td>WORKSPACE</td><td>AUTO</td><td>{@code acceptEdits}</td><td>—</td></tr>
 *   <tr><td>WORKSPACE</td><td>SILENT</td><td>{@code acceptEdits}</td><td>—</td></tr>
 *   <tr><td>FULL</td><td>ASK</td><td>{@code default}</td><td>—</td></tr>
 *   <tr><td>FULL</td><td>AUTO</td><td>{@code default}</td><td>—</td></tr>
 *   <tr><td>FULL</td><td>SILENT</td><td>—</td><td>{@code dangerously-skip-permissions=true}</td></tr>
 * </table>
 */
public class ClaudeSecurityMapper implements SecurityMapper {

    /** Key for the permission-mode value in the returned metadata map. */
    public static final String KEY_PERMISSION_MODE = "claude.permission-mode";

    /** Key for dangerously-skip-permissions flag. */
    public static final String KEY_SKIP_PERMISSIONS = "claude.dangerously-skip-permissions";

    @Override
    public String getAgentType() {
        return "claude-code";
    }

    @Override
    public Map<String, String> mapSecurity(SandboxLevel level, ApprovalPolicy policy) {
        return switch (level) {
            case READ_ONLY -> Map.of(KEY_PERMISSION_MODE, "plan");
            case WORKSPACE -> switch (policy) {
                case ASK -> Map.of(KEY_PERMISSION_MODE, "default");
                case AUTO, SILENT -> Map.of(KEY_PERMISSION_MODE, "acceptEdits");
                default -> Map.of(KEY_PERMISSION_MODE, "default");
            };
            case FULL -> switch (policy) {
                case ASK, AUTO -> Map.of(KEY_PERMISSION_MODE, "default");
                case SILENT -> Map.of(KEY_SKIP_PERMISSIONS, "true");
                default -> Map.of(KEY_PERMISSION_MODE, "default");
            };
        };
    }
}
