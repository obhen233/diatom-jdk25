package com.github.obhen233.core.skill;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.Assert.*;

public class SkillManagerTest {

    private Path tempDir;
    private Path globalSkillsDir;
    private Path projectSkillsDir;
    private String originalUserHome;

    @Before
    public void setUp() throws IOException {
        // Save original user.home
        originalUserHome = System.getProperty("user.home");

        // Create temp directory structure
        tempDir = Files.createTempDirectory("skill-manager-test-");
        globalSkillsDir = tempDir.resolve(".diatom").resolve("skills");
        projectSkillsDir = tempDir.resolve("project").resolve(".diatom").resolve("skills");

        // Set user.home to our temp dir
        System.setProperty("user.home", tempDir.toString());

        // Create directories
        Files.createDirectories(globalSkillsDir);
        Files.createDirectories(projectSkillsDir);
    }

    @After
    public void tearDown() {
        System.setProperty("user.home", originalUserHome);
        // Clean up temp directory
        deleteRecursively(tempDir);
    }

    private void deleteRecursively(Path path) {
        try {
            if (Files.isDirectory(path)) {
                Files.list(path).forEach(this::deleteRecursively);
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private void createSkillFile(Path dir, String name, String description, String version,
                                 boolean enabled, int priority, List<String> triggers, String body) throws IOException {
        createSkillFile(dir, name, description, version, enabled, priority, triggers, body, null);
    }

    private void createSkillFile(Path dir, String name, String description, String version,
                                 boolean enabled, int priority, List<String> triggers, String body,
                                 String kind) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append("\n");
        sb.append("description: ").append(description).append("\n");
        sb.append("version: ").append(version).append("\n");
        sb.append("enabled: ").append(enabled).append("\n");
        sb.append("priority: ").append(priority).append("\n");
        if (kind != null) {
            sb.append("kind: ").append(kind).append("\n");
        }
        if (triggers != null && !triggers.isEmpty()) {
            sb.append("triggers:\n");
            for (String t : triggers) {
                // Quote triggers that start with * (YAML anchor syntax issue)
                if (t.startsWith("*.")) {
                    sb.append("  - \"").append(t).append("\"\n");
                } else {
                    sb.append("  - ").append(t).append("\n");
                }
            }
        }
        sb.append("---\n\n");
        sb.append(body);
        Files.write(dir.resolve(name + ".skill.md"), sb.toString().getBytes());
    }

    private void createSkillFileWithProfile(Path dir, String name, String description, String version,
                                            boolean enabled, int priority, String profile,
                                            List<String> triggers, String body) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append("\n");
        sb.append("description: ").append(description).append("\n");
        sb.append("version: ").append(version).append("\n");
        sb.append("enabled: ").append(enabled).append("\n");
        sb.append("priority: ").append(priority).append("\n");
        if (profile != null) {
            sb.append("profile: ").append(profile).append("\n");
        }
        if (triggers != null && !triggers.isEmpty()) {
            sb.append("triggers:\n");
            for (String t : triggers) {
                if (t.startsWith("*.")) {
                    sb.append("  - \"").append(t).append("\"\n");
                } else {
                    sb.append("  - ").append(t).append("\n");
                }
            }
        }
        sb.append("---\n\n");
        sb.append(body);
        Files.write(dir.resolve(name + ".skill.md"), sb.toString().getBytes());
    }

    @Test
    public void testLoadSkills() throws IOException {
        // Create a skill in global directory
        createSkillFile(globalSkillsDir, "global-skill", "Global skill", "1.0.0",
                true, 0, Arrays.asList("global"), "Global skill body");

        // Create a skill with the same name in project directory (higher priority)
        createSkillFile(projectSkillsDir, "global-skill", "Project override", "2.0.0",
                true, 0, Arrays.asList("global"), "Project override body");

        // Create a skill only in project directory
        createSkillFile(projectSkillsDir, "project-only", "Only in project", "1.0.0",
                true, 0, Arrays.asList("project"), "Project only body");

        SkillManager manager = new SkillManager(Paths.get(tempDir.toString(), "project"));

        // Verify three-layer loading — project override should replace global
        assertEquals(2, manager.getSkills().size());

        Skill overridden = manager.getSkills().stream()
                .filter(s -> "global-skill".equals(s.getName()))
                .findFirst().orElse(null);
        assertNotNull(overridden);
        assertEquals("Project override", overridden.getDescription());
        assertEquals("2.0.0", overridden.getVersion());
    }

    @Test
    public void testMatchSkills() throws IOException {
        createSkillFile(globalSkillsDir, "java-helper", "Java helper", "1.0.0",
                true, 0, Arrays.asList("java", "spring"), "Java helper body");
        createSkillFile(globalSkillsDir, "python-helper", "Python helper", "1.0.0",
                true, 0, Arrays.asList("python"), "Python helper body");

        SkillManager manager = new SkillManager();

        // Match by keyword "java"
        List<Skill> matched = manager.matchSkills("need java help", new ArrayList<>());
        assertEquals(1, matched.size());
        assertEquals("java-helper", matched.get(0).getName());

        // Query without matching triggers returns empty
        matched = manager.matchSkills("something random", new ArrayList<>());
        assertEquals(0, matched.size());
    }

    @Test
    public void testMatchSkillsByFileExtension() throws IOException {
        createSkillFile(globalSkillsDir, "xml-helper", "XML helper", "1.0.0",
                true, 0, Arrays.asList("*.xml"), "XML helper body");

        SkillManager manager = new SkillManager();

        List<Skill> matched = manager.matchSkills("edit config", Arrays.asList("pom.xml"));
        assertEquals(1, matched.size());
        assertEquals("xml-helper", matched.get(0).getName());

        // No matching files
        matched = manager.matchSkills("edit config", Arrays.asList("pom.java"));
        assertEquals(0, matched.size());
    }

    @Test
    public void testBuildContext() throws IOException {
        createSkillFile(globalSkillsDir, "test-skill", "Test skill", "1.0.0",
                true, 0, Arrays.asList("test"), "Test body content");

        SkillManager manager = new SkillManager();
        List<Skill> matched = manager.matchSkills("test", new ArrayList<>());
        String context = manager.buildContext(matched);

        assertTrue(context.contains("test-skill"));
        assertTrue(context.contains("Test skill"));
        assertTrue(context.contains("Test body content"));
        assertTrue(context.contains("## Activated Skills"));
    }

    @Test
    public void testBuildContextEmpty() {
        SkillManager manager = new SkillManager();
        String context = manager.buildContext(new ArrayList<>());
        assertEquals("", context);
    }

    @Test
    public void testDisabledSkillNotMatched() throws IOException {
        createSkillFile(globalSkillsDir, "disabled-skill", "Disabled skill", "1.0.0",
                false, 0, Arrays.asList("secret"), "Should not appear");

        SkillManager manager = new SkillManager();
        List<Skill> matched = manager.matchSkills("secret", new ArrayList<>());
        // Disabled skill should not match even though keyword matches
        assertTrue(matched.stream().noneMatch(s -> "disabled-skill".equals(s.getName())));
    }

    @Test
    public void testPriorityOrdering() throws IOException {
        createSkillFile(globalSkillsDir, "low-priority", "Low priority", "1.0.0",
                true, 0, null, "Low priority body", "system");
        createSkillFile(globalSkillsDir, "high-priority", "High priority", "1.0.0",
                true, 10, null, "High priority body", "system");

        SkillManager manager = new SkillManager();
        List<Skill> matched = manager.matchSkills("anything", new ArrayList<>());

        // High priority should come first
        assertEquals("high-priority", matched.get(0).getName());
        assertEquals("low-priority", matched.get(1).getName());
    }

    @Test
    public void testConcurrentAccess() throws IOException, InterruptedException {
        createSkillFile(globalSkillsDir, "concurrent-skill", "Concurrent test", "1.0.0",
                true, 0, Arrays.asList("test"), "Concurrent body");

        SkillManager manager = new SkillManager();

        // Concurrent reads while reloading
        final int THREADS = 10;
        Thread[] readers = new Thread[THREADS];
        final boolean[] errors = new boolean[THREADS];

        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            readers[idx] = new Thread(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        manager.matchSkills("test", new ArrayList<>());
                        manager.getSkills();
                        manager.listSkills();
                    }
                } catch (Exception e) {
                    errors[idx] = true;
                }
            });
        }

        // Start readers
        for (Thread t : readers) t.start();

        // Reload concurrently
        for (int i = 0; i < 20; i++) {
            manager.reload();
            Thread.yield();
        }

        // Wait for readers
        for (Thread t : readers) t.join();

        for (int i = 0; i < THREADS; i++) {
            assertFalse("Thread " + i + " encountered an error", errors[i]);
        }
    }

    @Test
    public void testListSkills() throws IOException {
        createSkillFile(globalSkillsDir, "alpha-skill", "Alpha skill", "1.0.0",
                true, 0, null, "Alpha body");
        createSkillFile(globalSkillsDir, "beta-skill", "Beta skill", "1.0.0",
                false, 0, null, "Beta body");

        SkillManager manager = new SkillManager();
        String output = manager.listSkills();

        assertTrue("Should contain Global source header", output.contains("Global"));
        assertTrue("Should contain alpha-skill", output.contains("alpha-skill"));
        assertTrue("Should contain Alpha skill", output.contains("Alpha skill"));
        assertTrue("Should contain beta-skill", output.contains("beta-skill"));
        assertTrue("Should contain [disabled]", output.contains("[disabled]"));
    }

    @Test
    public void testGetSkillContent() throws IOException {
        createSkillFile(globalSkillsDir, "get-test", "Get test", "1.0.0",
                true, 0, Arrays.asList("test"), "Get test body");

        SkillManager manager = new SkillManager();
        String content = manager.getSkillContent("get-test");

        assertTrue(content.contains("name: get-test"));
        assertTrue(content.contains("description: Get test"));
        assertTrue(content.contains("Get test body"));

        // Non-existent skill
        content = manager.getSkillContent("non-existent");
        assertTrue(content.contains("Skill not found"));
    }

    @Test
    public void testProfileFiltering() throws IOException {
        createSkillFileWithProfile(globalSkillsDir, "java-dev-skill", "Java dev helper", "1.0.0",
                true, 0, "java-dev", Arrays.asList("maven"), "Java dev body");
        createSkillFileWithProfile(globalSkillsDir, "python-dev-skill", "Python dev helper", "1.0.0",
                true, 0, "python-dev", Arrays.asList("pip"), "Python dev body");
        createSkillFile(globalSkillsDir, "common-skill", "Common helper", "1.0.0",
                true, 0, null, "Common body", "system");

        SkillManager manager = new SkillManager();

        // No profile active — match by trigger keywords
        List<Skill> matched = manager.matchSkills("maven", new ArrayList<>());
        assertEquals(2, matched.size()); // java-dev-skill (trigger "maven") + common-skill (no triggers)

        // Activate java-dev profile
        manager.activateProfile("java-dev");
        matched = manager.matchSkills("maven", new ArrayList<>());
        assertEquals(2, matched.size()); // java-dev-skill + common-skill (no profile)
        assertTrue(matched.stream().anyMatch(s -> "java-dev-skill".equals(s.getName())));
        assertTrue(matched.stream().anyMatch(s -> "common-skill".equals(s.getName())));
        assertTrue(matched.stream().noneMatch(s -> "python-dev-skill".equals(s.getName())));
    }

    @Test
    public void testProfileActivateDeactivate() throws IOException {
        createSkillFileWithProfile(globalSkillsDir, "profile-skill", "Profile skill", "1.0.0",
                true, 0, "test-profile", Arrays.asList("test"), "Profile body");
        createSkillFile(globalSkillsDir, "no-profile-skill", "No profile", "1.0.0",
                true, 0, Arrays.asList("test"), "No profile body");

        SkillManager manager = new SkillManager();

        // Activate profile
        manager.activateProfile("test-profile");
        assertEquals("test-profile", manager.getActiveProfile());
        List<Skill> matched = manager.matchSkills("test", new ArrayList<>());
        assertEquals(2, matched.size()); // profile-skill + no-profile-skill

        // Deactivate profile
        manager.deactivateProfile();
        assertNull(manager.getActiveProfile());
        matched = manager.matchSkills("test", new ArrayList<>());
        assertEquals(2, matched.size()); // all
    }

    @Test
    public void testKindDefaultIsUser() throws IOException {
        createSkillFile(globalSkillsDir, "user-skill", "User skill", "1.0.0",
                true, 0, null, "User body");

        SkillManager manager = new SkillManager();
        Skill skill = manager.getSkills().iterator().next();
        assertEquals("user", skill.getKind());
    }

    @Test
    public void testSystemSkillNotFilteredByProfile() throws IOException {
        // Create a system skill with a specific profile
        String content = "---\nname: sys-skill\ndescription: System skill\nversion: 1.0.0\nkind: system\nprofile: hidden\n---\n\nSystem body";
        Files.write(globalSkillsDir.resolve("sys-skill.skill.md"), content.getBytes());

        createSkillFile(globalSkillsDir, "user-skill", "User skill", "1.0.0",
                true, 0, Arrays.asList("anything"), "User body");

        SkillManager manager = new SkillManager();

        // Activate a different profile — system skill should still be available
        manager.activateProfile("other-profile");
        List<Skill> matched = manager.matchSkills("anything", new ArrayList<>());
        assertTrue(matched.stream().anyMatch(s -> "sys-skill".equals(s.getName())));
        assertTrue(matched.stream().anyMatch(s -> "user-skill".equals(s.getName())));
    }

    @Test
    public void testBuildContextWithVariables() throws IOException {
        // Create a skill with variables
        String content = "---\nname: var-skill\ndescription: Variable test\nversion: 1.0.0\nkind: system\n" +
                "variables:\n  env:\n    default: staging\n  server:\n    default: localhost\n---\n\n" +
                "Deploy to {{env}} on {{server}}.";
        Files.write(globalSkillsDir.resolve("var-skill.skill.md"), content.getBytes());

        SkillManager manager = new SkillManager();
        List<Skill> matched = manager.matchSkills("anything", new ArrayList<>());

        // Without explicit variables — uses defaults
        String context = manager.buildContext(matched);
        assertTrue(context.contains("Deploy to staging on localhost."));

        // With explicit variables — overrides defaults
        Map<String, String> vars = new HashMap<>();
        vars.put("env", "production");
        vars.put("server", "192.168.1.1");
        context = manager.buildContext(matched, vars);
        assertTrue(context.contains("Deploy to production on 192.168.1.1."));
    }

    @Test
    public void testBuildContextSizeLimit() throws IOException {
        // Create a skill with a body larger than MAX_SKILL_BODY_SIZE
        StringBuilder largeBody = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            largeBody.append("x");
        }
        String content = "---\nname: large-skill\ndescription: Large skill\nversion: 1.0.0\nkind: system\n---\n\n" + largeBody;
        Files.write(globalSkillsDir.resolve("large-skill.skill.md"), content.getBytes());

        SkillManager manager = new SkillManager();
        List<Skill> matched = manager.matchSkills("anything", new ArrayList<>());
        String context = manager.buildContext(matched);

        assertTrue(context.contains("truncated"));
        assertTrue(context.contains("5000 chars total"));
    }

    @Test
    public void testListSkillsFiltersSystem() throws IOException {
        String content = "---\nname: system-thing\ndescription: System thing\nversion: 1.0.0\nkind: system\n---\n\nSystem body";
        Files.write(globalSkillsDir.resolve("system-thing.skill.md"), content.getBytes());
        createSkillFile(globalSkillsDir, "user-thing", "User thing", "1.0.0",
                true, 0, null, "User body");

        SkillManager manager = new SkillManager();
        String output = manager.listSkills();
        assertFalse(output.contains("system-thing"));
        assertTrue(output.contains("user-thing"));

        String allOutput = manager.listSkills(true);
        assertTrue(allOutput.contains("system-thing"));
        assertTrue(allOutput.contains("user-thing"));
    }

    @Test
    public void testSemanticMatchFlagDefaultsDisabled() {
        SkillManager manager = new SkillManager();
        assertFalse(manager.isSemanticMatchEnabled());

        manager.setSemanticMatchEnabled(true);
        assertTrue(manager.isSemanticMatchEnabled());
    }

    @Test
    public void testL1MatchingWorksWithoutL2() throws IOException {
        // L1 keyword matching should work normally even when LLM client is not set
        createSkillFile(globalSkillsDir, "test-skill", "Test skill", "1.0.0",
                true, 0, Arrays.asList("test"), "Test body");

        SkillManager manager = new SkillManager();
        manager.setSemanticMatchEnabled(true);
        // No LLM client set — L2 will be skipped silently

        List<Skill> matched = manager.matchSkills("test", new ArrayList<>());
        assertEquals(1, matched.size());
        assertEquals("test-skill", matched.get(0).getName());
    }
}
