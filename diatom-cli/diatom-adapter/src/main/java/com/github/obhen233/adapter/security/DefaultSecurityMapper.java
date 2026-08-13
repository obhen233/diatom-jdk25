package com.github.obhen233.adapter.security;

import com.github.obhen233.adapter.spi.ApprovalPolicy;
import com.github.obhen233.adapter.spi.SandboxLevel;
import com.github.obhen233.adapter.spi.SecurityMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * Default/fallback {@link SecurityMapper} implementation.
 *
 * <p>Returns a simple generic mapping from diatom security enums to
 * string key-value pairs. Used when no agent-specific mapper is registered.</p>
 */
public class DefaultSecurityMapper implements SecurityMapper {

    @Override
    public Map<String, String> mapSecurity(SandboxLevel level, ApprovalPolicy policy) {
        Map<String, String> result = new HashMap<>();
        result.put("sandbox", level.name().toLowerCase().replace('_', '-'));
        result.put("auto-approve", String.valueOf(policy != ApprovalPolicy.ASK));
        result.put("scope", level == SandboxLevel.READ_ONLY ? "read" : "read-write");
        return result;
    }

    @Override
    public String getAgentType() {
        return null; // fallback/default mapper
    }
}
