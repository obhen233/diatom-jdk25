package com.github.obhen233.core.agent.loop;

import com.github.obhen233.core.skill.Skill;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class DefaultPermissionCheckerSkillTest {

    private final DefaultPermissionChecker checker = new DefaultPermissionChecker(
            null, null, null, null, null);

    @Test
    public void testNoActiveSkillsReturnsNull() {
        String result = checker.checkSkillToolAllowed("write_file", new ArrayList<>());
        assertNull("No active skills should return null", result);
    }

    @Test
    public void testNullActiveSkillsReturnsNull() {
        String result = checker.checkSkillToolAllowed("write_file", null);
        assertNull("Null skills should return null", result);
    }

    @Test
    public void testSkillAllowsToolReturnsNull() {
        Skill skill = new Skill();
        skill.setName("restricted");
        skill.setAllowedTools("read_file, search_files, write_file");

        String result = checker.checkSkillToolAllowed("write_file", Arrays.asList(skill));
        assertNull("Tool in allowed list should return null", result);
    }

    @Test
    public void testSkillBlocksToolReturnsMessage() {
        Skill skill = new Skill();
        skill.setName("restricted");
        skill.setAllowedTools("read_file, search_files");

        String result = checker.checkSkillToolAllowed("write_file", Arrays.asList(skill));
        assertNotNull("Tool not in allowed list should return error", result);
        assertTrue(result.contains("write_file"));
        assertTrue(result.contains("not allowed"));
    }

    @Test
    public void testMultipleSkillsUnion() {
        Skill skill1 = new Skill();
        skill1.setName("read-only");
        skill1.setAllowedTools("read_file");

        Skill skill2 = new Skill();
        skill2.setName("write-allowed");
        skill2.setAllowedTools("write_file");

        // Tool allowed by any skill → null
        String result = checker.checkSkillToolAllowed("write_file", Arrays.asList(skill1, skill2));
        assertNull("Tool allowed by at least one skill", result);

        // Tool not allowed by any skill → error
        result = checker.checkSkillToolAllowed("delete_file", Arrays.asList(skill1, skill2));
        assertNotNull("Tool not allowed by any skill", result);
    }

    @Test
    public void testNoRestrictionsOnAnySkill() {
        Skill skill1 = new Skill();
        skill1.setName("no-restrictions-1");
        // allowedTools is null — no restrictions

        Skill skill2 = new Skill();
        skill2.setName("no-restrictions-2");
        skill2.setAllowedTools("");

        String result = checker.checkSkillToolAllowed("any_tool", Arrays.asList(skill1, skill2));
        assertNull("No skill with restrictions should return null", result);
    }

    @Test
    public void testSystemSkillNotConstrained() {
        Skill systemSkill = new Skill();
        systemSkill.setName("system-helper");
        systemSkill.setKind("system");
        systemSkill.setAllowedTools("read_file"); // Only allows read_file

        Skill userSkill = new Skill();
        userSkill.setName("user-skill");
        userSkill.setKind("user");
        userSkill.setAllowedTools("read_file"); // Only allows read_file

        // System skill's allowedTools should not constrain user skills
        // But the userSkill's restrictions apply
        String result = checker.checkSkillToolAllowed("write_file", Arrays.asList(systemSkill));
        assertNull("System skill restrictions don't apply", result);

        result = checker.checkSkillToolAllowed("write_file", Arrays.asList(systemSkill, userSkill));
        assertNotNull("User skill restrictions apply", result);
    }

    @Test
    public void testAllowedToolsTrimHandling() {
        Skill skill = new Skill();
        skill.setName("trim-test");
        skill.setAllowedTools("  read_file ,  write_file  ");

        assertNull("read_file should be allowed (trimmed)",
                checker.checkSkillToolAllowed("read_file", Arrays.asList(skill)));
        assertNull("write_file should be allowed (trimmed)",
                checker.checkSkillToolAllowed("write_file", Arrays.asList(skill)));
        assertNotNull("delete_file should be blocked",
                checker.checkSkillToolAllowed("delete_file", Arrays.asList(skill)));
    }

    @Test
    public void testGetActiveSkillsWithRestrictions() {
        // Integration check: verify the PermissionChecker interface method signature
        assertNotNull(checker);
        assertTrue(checker instanceof PermissionChecker);
    }
}
