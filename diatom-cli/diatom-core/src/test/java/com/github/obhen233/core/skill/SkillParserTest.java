package com.github.obhen233.core.skill;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SkillParserTest {

    @Test
    public void testParseFullFrontmatter() throws IOException {
        Path tempFile = Files.createTempFile("skill-test-", ".skill.md");
        try {
            String content = "---\n" +
                    "name: test-skill\n" +
                    "description: Test description\n" +
                    "version: 2.1.0\n" +
                    "enabled: true\n" +
                    "priority: 5\n" +
                    "triggers:\n" +
                    "  - test\n" +
                    "  - example\n" +
                    "allowed-tools: read_file, write_file\n" +
                    "profile: java-dev\n" +
                    "kind: user\n" +
                    "---\n\n" +
                    "This is the skill body.";
            Files.write(tempFile, content.getBytes());

            Skill skill = SkillParser.parse(tempFile);

            assertEquals("test-skill", skill.getName());
            assertEquals("Test description", skill.getDescription());
            assertEquals("2.1.0", skill.getVersion());
            assertTrue(skill.isEnabled());
            assertEquals(5, skill.getPriority());
            assertEquals(Arrays.asList("test", "example"), skill.getTriggers());
            assertEquals("read_file, write_file", skill.getAllowedTools());
            assertEquals("java-dev", skill.getProfile());
            assertEquals("user", skill.getKind());
            assertEquals("This is the skill body.", skill.getBody());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testParseMinimalFrontmatter() throws IOException {
        Path tempFile = Files.createTempFile("skill-min-", ".skill.md");
        try {
            String content = "---\n" +
                    "name: minimal-skill\n" +
                    "---\n\n" +
                    "Minimal body.";
            Files.write(tempFile, content.getBytes());

            Skill skill = SkillParser.parse(tempFile);

            assertEquals("minimal-skill", skill.getName());
            assertNull(skill.getDescription());
            assertNull(skill.getVersion());
            assertTrue(skill.isEnabled()); // default
            assertEquals(0, skill.getPriority()); // default
            assertNull(skill.getTriggers());
            assertNull(skill.getAllowedTools());
            assertNull(skill.getProfile());
            assertEquals("user", skill.getKind()); // default
            assertEquals("Minimal body.", skill.getBody());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testParseNoFrontmatter() throws IOException {
        Path tempFile = Files.createTempFile("skill-nofm-", ".skill.md");
        try {
            String content = "Just a body without frontmatter.";
            Files.write(tempFile, content.getBytes());

            Skill skill = SkillParser.parse(tempFile);

            assertNull(skill.getName());
            assertNull(skill.getDescription());
            assertEquals("Just a body without frontmatter.", skill.getBody());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testParseWithVariables() throws IOException {
        Path tempFile = Files.createTempFile("skill-var-", ".skill.md");
        try {
            String content = "---\n" +
                    "name: var-skill\n" +
                    "variables:\n" +
                    "  env:\n" +
                    "    default: staging\n" +
                    "    description: Deploy environment\n" +
                    "  server:\n" +
                    "    required: true\n" +
                    "    description: Target server\n" +
                    "---\n\n" +
                    "Deploy to {{env}} on {{server}}.";
            Files.write(tempFile, content.getBytes());

            Skill skill = SkillParser.parse(tempFile);

            assertEquals("var-skill", skill.getName());
            assertNotNull(skill.getVariables());
            assertTrue(skill.getVariables().containsKey("env"));
            assertTrue(skill.getVariables().containsKey("server"));

            @SuppressWarnings("unchecked")
            Map<String, Object> envDef = (Map<String, Object>) skill.getVariables().get("env");
            assertEquals("staging", envDef.get("default"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testParseKindSystem() throws IOException {
        Path tempFile = Files.createTempFile("skill-kind-", ".skill.md");
        try {
            String content = "---\n" +
                    "name: system-helper\n" +
                    "kind: system\n" +
                    "---\n\n" +
                    "System body.";
            Files.write(tempFile, content.getBytes());

            Skill skill = SkillParser.parse(tempFile);
            assertEquals("system", skill.getKind());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testParseWithProfile() throws IOException {
        Path tempFile = Files.createTempFile("skill-prof-", ".skill.md");
        try {
            String content = "---\n" +
                    "name: profiled-skill\n" +
                    "profile: devops\n" +
                    "---\n\n" +
                    "Profile body.";
            Files.write(tempFile, content.getBytes());

            Skill skill = SkillParser.parse(tempFile);
            assertEquals("profiled-skill", skill.getName());
            assertEquals("devops", skill.getProfile());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testParseWithFilePath() throws IOException {
        Path tempFile = Files.createTempFile("skill-path-", ".skill.md");
        try {
            Files.write(tempFile, "---\nname: path-skill\n---\n\nBody.".getBytes());

            Skill skill = SkillParser.parse(tempFile);
            assertEquals(tempFile, skill.getFilePath());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testParseMalformedYaml() throws IOException {
        Path tempFile = Files.createTempFile("skill-bad-", ".skill.md");
        try {
            // Malformed YAML - name has invalid syntax
            String content = "---\n" +
                    "name: [unclosed list\n" +
                    "---\n\n" +
                    "Body.";
            Files.write(tempFile, content.getBytes());

            // Should not throw, gracefully handle parse failure
            Skill skill = SkillParser.parse(tempFile);
            assertNull(skill.getName()); // name parsing failed
            assertNotNull(skill.getBody());
            assertEquals("Body.", skill.getBody());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
