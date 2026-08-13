package com.github.obhen233.core.skill;

import org.junit.Test;

import static org.junit.Assert.*;

public class SystemPromptManagerTest {

    @Test
    public void testStaleSelfUpdateSectionIsNotCurrent() {
        assertFalse(SystemPromptManager.isSelfUpdateSectionCurrent("Core read-only. Workflow compile_sources."));
    }

    @Test
    public void testCurrentSelfUpdateSectionRequiresKeyTerms() {
        assertTrue(SystemPromptManager.isSelfUpdateSectionCurrent(
                "Read sources/core-spi.json, call registry.scanObject(new Tools()), annotate methods with ToolMethod."));
    }
}
