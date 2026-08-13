package com.github.obhen233.core.gateway.profile;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class CapabilityProfileTest {

    @Test
    public void testCapabilityLevelEnumValues() {
        assertEquals(3, CapabilityProfile.CapabilityLevel.values().length);
        assertEquals(CapabilityProfile.CapabilityLevel.REQUIRED, CapabilityProfile.CapabilityLevel.valueOf("REQUIRED"));
        assertEquals(CapabilityProfile.CapabilityLevel.PREFERRED, CapabilityProfile.CapabilityLevel.valueOf("PREFERRED"));
        assertEquals(CapabilityProfile.CapabilityLevel.NORMAL, CapabilityProfile.CapabilityLevel.valueOf("NORMAL"));
    }

    @Test
    public void testDefaultValues() {
        CapabilityProfile profile = new CapabilityProfile();
        assertNotNull(profile.getStrengths());
        assertTrue(profile.getStrengths().isEmpty());
        assertNotNull(profile.getBoundaries());
        assertTrue(profile.getBoundaries().isEmpty());
        assertNotNull(profile.getCapabilityLevels());
        assertTrue(profile.getCapabilityLevels().isEmpty());
        assertEquals(0, profile.getMaxSteps());
        assertEquals(0, profile.getMaxTokens());
        assertEquals(0, profile.getMaxOutputTokens());
        assertFalse(profile.isSupportsToolCalls());
        assertFalse(profile.isSupportsStreaming());
    }

    @Test
    public void testCapabilityLevelsGetterSetter() {
        CapabilityProfile profile = new CapabilityProfile();
        Map<String, CapabilityProfile.CapabilityLevel> levels = new HashMap<>();
        levels.put("feature_development", CapabilityProfile.CapabilityLevel.REQUIRED);
        levels.put("bug_fix", CapabilityProfile.CapabilityLevel.PREFERRED);
        levels.put("code_review", CapabilityProfile.CapabilityLevel.NORMAL);
        profile.setCapabilityLevels(levels);

        assertEquals(3, profile.getCapabilityLevels().size());
        assertEquals(CapabilityProfile.CapabilityLevel.REQUIRED, profile.getCapabilityLevels().get("feature_development"));
        assertEquals(CapabilityProfile.CapabilityLevel.PREFERRED, profile.getCapabilityLevels().get("bug_fix"));
        assertEquals(CapabilityProfile.CapabilityLevel.NORMAL, profile.getCapabilityLevels().get("code_review"));
    }

    @Test
    public void testBoundariesField() {
        CapabilityProfile profile = new CapabilityProfile();
        assertNotNull(profile.getBoundaries());
        profile.getBoundaries().add("No internet access");
        profile.getBoundaries().add("File operations limited to workspace");
        assertEquals(2, profile.getBoundaries().size());
    }

    @Test
    public void testMaxTokensField() {
        CapabilityProfile profile = new CapabilityProfile();
        profile.setMaxTokens(128000);
        assertEquals(128000, profile.getMaxTokens());
    }

    @Test
    public void testMaxOutputTokensField() {
        CapabilityProfile profile = new CapabilityProfile();
        profile.setMaxOutputTokens(16384);
        assertEquals(16384, profile.getMaxOutputTokens());
    }

    @Test
    public void testSupportsToolCallsField() {
        CapabilityProfile profile = new CapabilityProfile();
        profile.setSupportsToolCalls(true);
        assertTrue(profile.isSupportsToolCalls());
    }

    @Test
    public void testSupportsStreamingField() {
        CapabilityProfile profile = new CapabilityProfile();
        profile.setSupportsStreaming(true);
        assertTrue(profile.isSupportsStreaming());
    }

    @Test
    public void testApiProviderField() {
        CapabilityProfile profile = new CapabilityProfile();
        profile.setApiProvider("anthropic");
        assertEquals("anthropic", profile.getApiProvider());
    }
}
