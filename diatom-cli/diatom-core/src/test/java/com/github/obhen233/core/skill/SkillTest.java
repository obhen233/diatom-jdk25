package com.github.obhen233.core.skill;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class SkillTest {

    @Test
    public void testMatchesKeywordTrigger() {
        Skill skill = new Skill();
        skill.setName("test-skill");
        skill.setEnabled(true);
        skill.setTriggers(Arrays.asList("test", "example"));

        assertTrue(skill.matches("this is a test", new ArrayList<>()));
        assertTrue(skill.matches("example query", new ArrayList<>()));
        assertFalse(skill.matches("random query", new ArrayList<>()));
    }

    @Test
    public void testMatchesFileExtension() {
        Skill skill = new Skill();
        skill.setName("xml-helper");
        skill.setEnabled(true);
        skill.setTriggers(Arrays.asList("*.xml"));

        assertTrue(skill.matches("edit config", Arrays.asList("pom.xml")));
        assertTrue(skill.matches("edit config", Arrays.asList("data.xml", "readme.txt")));
        assertFalse(skill.matches("edit config", Arrays.asList("pom.java")));
        assertFalse(skill.matches("edit config", new ArrayList<>()));
    }

    @Test
    public void testMatchesNullTriggers() {
        Skill skill = new Skill();
        skill.setName("always-active");
        skill.setEnabled(true);
        skill.setTriggers(null);
        skill.setKind("system"); // system-kind skills match without triggers

        assertTrue(skill.matches("anything", new ArrayList<>()));
        assertTrue(skill.matches("", new ArrayList<>()));
    }

    @Test
    public void testMatchesEmptyTriggers() {
        Skill skill = new Skill();
        skill.setName("always-active");
        skill.setEnabled(true);
        skill.setTriggers(new ArrayList<>());
        skill.setKind("system"); // system-kind skills match without triggers

        assertTrue(skill.matches("anything", new ArrayList<>()));
    }

    @Test
    public void testUserSkillWithoutTriggersDoesNotMatch() {
        Skill skill = new Skill();
        skill.setName("user-skill-no-trigger");
        skill.setEnabled(true);
        skill.setTriggers(null);
        // Default kind is "user" — should NOT automatically match

        assertFalse(skill.matches("anything", new ArrayList<>()));
    }

    @Test
    public void testDisabledSkillNeverMatches() {
        Skill skill = new Skill();
        skill.setName("disabled-skill");
        skill.setEnabled(false);
        skill.setTriggers(null);

        assertFalse(skill.matches("anything", new ArrayList<>()));
    }

    @Test
    public void testDisabledSkillWithKeyword() {
        Skill skill = new Skill();
        skill.setName("disabled-skill");
        skill.setEnabled(false);
        skill.setTriggers(Arrays.asList("keyword"));

        assertFalse(skill.matches("keyword in query", new ArrayList<>()));
    }

    @Test
    public void testMultipleTriggersOrLogic() {
        Skill skill = new Skill();
        skill.setName("multi-trigger");
        skill.setEnabled(true);
        skill.setTriggers(Arrays.asList("deploy", "release", "rollback"));

        assertTrue(skill.matches("deploy the app", new ArrayList<>()));
        assertTrue(skill.matches("new release", new ArrayList<>()));
        assertTrue(skill.matches("rollback failed", new ArrayList<>()));
        assertFalse(skill.matches("build the app", new ArrayList<>()));
    }

    @Test
    public void testAllowedToolsField() {
        Skill skill = new Skill();
        skill.setName("restricted");
        skill.setAllowedTools("read_file, search_files");

        assertEquals("read_file, search_files", skill.getAllowedTools());
    }

    @Test
    public void testProfileField() {
        Skill skill = new Skill();
        skill.setName("profiled");
        skill.setProfile("java-dev");

        assertEquals("java-dev", skill.getProfile());
    }

    @Test
    public void testKindDefaultsToUser() {
        Skill skill = new Skill();
        skill.setName("default-kind");

        assertEquals("user", skill.getKind());
    }

    @Test
    public void testKindSetter() {
        Skill skill = new Skill();
        skill.setName("system-kind");
        skill.setKind("system");

        assertEquals("system", skill.getKind());
    }
}
