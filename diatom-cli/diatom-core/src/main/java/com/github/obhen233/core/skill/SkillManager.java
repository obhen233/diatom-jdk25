package com.github.obhen233.core.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ChatResponse;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Skills Manager supporting multi-level skill configurations.
 *
 * Level 1 - Global User:       ~/.diatom/skills/
 *   User-wide skills, effective for all projects.
 *
 * Level 2 - Self-Update:        {jar}/.diatom/skills/
 *   Skills for self-update functionality.
 *
 * Level 3 - Project Local:      {project}/.diatom/skills/
 *   Project-specific skills.
 *
 * Priority: Project Local > Self-Update > Global User
 * Same skill name at higher level overrides lower level.
 */
public class SkillManager {
    private static final Logger logger = LoggerFactory.getLogger(SkillManager.class);
    private static final String SKILLS_DIR_NAME = "skills";
    private static final String CONFIG_DIR_NAME = ".diatom";
    private static final String NEWLINE = System.lineSeparator();
    private static final int MAX_SKILL_BODY_SIZE = 4096;

    private final Path globalSkillsDir;  // ~/.diatom/skills
    private final Path jarSkillsDir;     // {jar}/.diatom/skills
    private final Path projectSkillsDir; // {project}/.diatom/skills (can be null)
    private volatile Map<String, Skill> skills = new LinkedHashMap<>(); // Use LinkedHashMap to maintain insertion order
    private volatile String activeProfile; // Current active profile, null means no filtering

    // Optional LLM client for L2 semantic matching
    private boolean semanticMatchEnabled;
    private AiHttpClient llmClient;
    private ModelAdapter llmModelAdapter;
    private String llmEndpoint;

    public SkillManager() {
        this(null);
    }

    public SkillManager(Path projectDir) {
        // Initialize paths
        this.globalSkillsDir = Paths.get(System.getProperty("user.home"), CONFIG_DIR_NAME, SKILLS_DIR_NAME);
        this.jarSkillsDir = detectJarSkillsDir();
        this.projectSkillsDir = projectDir != null ? Paths.get(projectDir.toString(), CONFIG_DIR_NAME, SKILLS_DIR_NAME) : null;

        // Ensure directories exist — each in own try-catch so one failure doesn't block others
        try {
            ensureDirExists(globalSkillsDir);
        } catch (IOException e) {
            logger.warn("Could not create global skills directory: {}", globalSkillsDir, e);
        }
        if (jarSkillsDir != null) {
            try {
                ensureDirExists(jarSkillsDir);
            } catch (IOException e) {
                logger.warn("Could not create JAR skills directory (may be read-only): {}", jarSkillsDir);
            }
        }
        if (projectSkillsDir != null) {
            try {
                ensureDirExists(projectSkillsDir);
            } catch (IOException e) {
                logger.warn("Could not create project skills directory: {}", projectSkillsDir, e);
            }
        }

        // Load all skills (independent of directory creation)
        try {
            loadAllSkills();
        } catch (Exception e) {
            logger.error("Failed to load skills", e);
        }
    }

    /**
     * Detect the JAR location and return the skills directory path
     */
    private Path detectJarSkillsDir() {
        try {
            String classPath = System.getProperty("java.class.path");
            String[] paths = classPath.split(File.pathSeparator);
            Path customLibDir = Paths.get(System.getProperty("user.home"), ".diatom", "custom", "lib");
            for (String path : paths) {
                if (path.endsWith(".jar")) {
                    // Skip custom-current.jar - it's the bootstrap wrapper, not a diatom core jar
                    if (path.contains("custom-current")) {
                        continue;
                    }
                    File jarFile = new File(path);
                    Path jarDir = jarFile.getParentFile() != null ? jarFile.getParentFile().toPath() : null;
                    if (jarDir != null) {
                        // Skip if JAR is in ~/.diatom/custom/lib - we don't want to create .diatom/skills there
                        if (jarDir.equals(customLibDir) || jarDir.startsWith(customLibDir)) {
                            continue;
                        }
                        return jarDir.resolve(CONFIG_DIR_NAME).resolve(SKILLS_DIR_NAME);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not detect JAR location: {}", e.getMessage());
        }
        return null;
    }

    private void ensureDirExists(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            logger.info("Created skills directory: {}", dir);
        }
    }

    /**
     * Load all skills into a new map and return it.
     * Used on initialization and reload (copy-on-write).
     */
    private Map<String, Skill> loadAllSkillsInto() {
        // Load in priority order: global (lowest) -> jar -> project (highest)
        Map<String, Skill> newSkills = new LinkedHashMap<>();
        List<Path> dirs = getDirsInPriorityOrder();

        for (Path dir : dirs) {
            if (dir == null || !Files.exists(dir)) {
                continue;
            }

            // Scan for *.skill.md files directly in the directory
            loadSkillFilesInto(dir, newSkills);
            // Also scan subdirectories one level deep for SKILL.md (Claude Code skill format)
            loadSkillsFromSubdirsInto(dir, newSkills);
        }

        logger.info("Total skills loaded: {} from {} directories", newSkills.size(), dirs.size());
        return newSkills;
    }

    private void loadAllSkills() {
        this.skills = loadAllSkillsInto();
    }

    /**
     * Load *.skill.md files directly from the given directory into the target map.
     */
    private void loadSkillFilesInto(Path dir, Map<String, Skill> target) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.skill.md")) {
            for (Path path : stream) {
                loadAndRegisterSkillInto(path, target);
            }
        } catch (IOException e) {
            logger.error("Failed to list skills directory: {}", dir, e);
        }
    }

    /**
     * Legacy wrapper - loads into the volatile skills map.
     */
    private void loadSkillFiles(Path dir) {
        loadSkillFilesInto(dir, skills);
    }

    /**
     * Scan subdirectories one level deep for SKILL.md files, loading into the target map.
     */
    private void loadSkillsFromSubdirsInto(Path dir, Map<String, Skill> target) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }

        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(dir, entry -> Files.isDirectory(entry))) {
            for (Path subDir : dirStream) {
                Path skillMd = subDir.resolve("SKILL.md");
                if (Files.exists(skillMd)) {
                    loadAndRegisterSkillInto(skillMd, target);
                }
            }
        } catch (IOException e) {
            logger.debug("Failed to list subdirectories in {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Legacy wrapper - loads into the volatile skills map.
     */
    private void loadSkillsFromSubdirs(Path dir) {
        loadSkillsFromSubdirsInto(dir, skills);
    }

    /**
     * Parse and register a skill into the target map.
     * If a skill with the same name already exists, the higher priority
     * (later loaded) version replaces it.
     */
    private void loadAndRegisterSkillInto(Path path, Map<String, Skill> target) {
        try {
            Skill skill = SkillParser.parse(path);
            if (skill.getName() == null || skill.getName().isEmpty()) {
                logger.debug("Skipping {} (no name in frontmatter)", path);
                return;
            }
            if (!target.containsKey(skill.getName())) {
                logger.info("Loaded skill '{}' from: {}", skill.getName(), path);
            } else {
                logger.debug("Overriding skill '{}' from {} (higher priority)", skill.getName(), path);
            }
            target.put(skill.getName(), skill);
        } catch (Exception e) {
            logger.warn("Failed to load skill from {}: {}", path, e.getMessage());
        }
    }

    /**
     * Legacy wrapper - registers into the volatile skills map.
     */
    private void loadAndRegisterSkill(Path path) {
        loadAndRegisterSkillInto(path, skills);
    }

    /**
     * Get config directories in priority order (lowest to highest)
     */
    private List<Path> getDirsInPriorityOrder() {
        List<Path> dirs = new java.util.ArrayList<>();
        dirs.add(globalSkillsDir);   // Lowest priority
        if (jarSkillsDir != null && !jarSkillsDir.equals(globalSkillsDir)) {
            dirs.add(jarSkillsDir);
        }
        if (projectSkillsDir != null && Files.exists(projectSkillsDir)) {
            dirs.add(projectSkillsDir);  // Highest priority
        }
        return dirs;
    }

    public List<Skill> matchSkills(String query, List<String> filePaths) {
        // L1: keyword matching (existing logic)
        String currentProfile = this.activeProfile;
        List<Skill> matched = skills.values().stream()
                .filter(s -> s.matches(query, filePaths))
                .filter(s -> currentProfile == null || s.getProfile() == null ||
                        currentProfile.equals(s.getProfile()) || "system".equals(s.getKind()))
                .sorted((a, b) -> b.getPriority() - a.getPriority())
                .collect(Collectors.toList());

        // L2: semantic matching if L1 returned no results and LLM is configured
        if (matched.isEmpty() && semanticMatchEnabled && llmClient != null && llmModelAdapter != null) {
            // Candidates: all enabled non-system skills (profile-filtered)
            List<Skill> candidates = skills.values().stream()
                    .filter(Skill::isEnabled)
                    .filter(s -> currentProfile == null || s.getProfile() == null ||
                            currentProfile.equals(s.getProfile()) || "system".equals(s.getKind()))
                    .collect(Collectors.toList());
            matched = matchWithLLM(query, candidates);
        }

        return matched;
    }

    /**
     * Activate a specific profile. Only skills matching this profile (or with no profile)
     * will be returned by matchSkills(). null profile skills are always available (backward compatible).
     */
    public void activateProfile(String profile) {
        this.activeProfile = profile;
    }

    /**
     * Deactivate the current profile, making all skills available for matching.
     */
    public void deactivateProfile() {
        this.activeProfile = null;
    }

    /**
     * Get the currently active profile name, or null if no profile is active.
     */
    public String getActiveProfile() {
        return activeProfile;
    }

    // ==================== L2 Semantic Matching ====================

    /**
     * Enable or disable semantic matching (L2 LLM-based skill selection).
     * Default is disabled.
     */
    public void setSemanticMatchEnabled(boolean enabled) {
        this.semanticMatchEnabled = enabled;
    }

    public boolean isSemanticMatchEnabled() {
        return semanticMatchEnabled;
    }

    /**
     * Set the LLM client for semantic matching.
     * @param client the AI HTTP client
     * @param adapter the model adapter for request/response formatting
     * @param endpoint the full API endpoint URL (e.g., "https://api.openai.com/v1/chat/completions")
     */
    public void setLlmClient(AiHttpClient client, ModelAdapter adapter, String endpoint) {
        this.llmClient = client;
        this.llmModelAdapter = adapter;
        this.llmEndpoint = endpoint;
    }

    /**
     * L2 semantic matching: ask the LLM which skills are relevant to the query.
     * Only called when L1 keyword matching returns no results.
     */
    List<Skill> matchWithLLM(String query, List<Skill> candidates) {
        if (candidates == null || candidates.isEmpty() || llmClient == null || llmModelAdapter == null) {
            return new ArrayList<>();
        }

        try {
            // Build a concise prompt for skill selection
            StringBuilder skillList = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                Skill s = candidates.get(i);
                skillList.append(i + 1).append(". ").append(s.getName());
                if (s.getDescription() != null && !s.getDescription().isEmpty()) {
                    skillList.append(" - ").append(s.getDescription());
                }
                skillList.append("\n");
            }

            String systemPrompt = "You are a skill matching system. Given a user query and a list of available skills, "
                    + "return a comma-separated list of skill names that are relevant to the query. "
                    + "If no skills are relevant, return \"NONE\". "
                    + "Respond with ONLY the skill names, nothing else.";

            String userMsg = "User query: " + query + "\n\nAvailable skills:\n" + skillList
                    + "\n\nWhich skills are relevant? Return comma-separated names or NONE.";

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatMessage("system", systemPrompt));
            messages.add(new ChatMessage("user", userMsg));

            String requestBody = llmModelAdapter.buildRequest(messages, null, false);
            String responseJson = llmClient.post(llmEndpoint, requestBody);
            ChatResponse response = llmModelAdapter.parseResponse(responseJson);

            if (response == null || response.getMessage() == null || response.getMessage().getContent() == null) {
                return new ArrayList<>();
            }

            String result = response.getMessage().getContent().trim();
            if ("NONE".equals(result)) {
                return new ArrayList<>();
            }

            // Parse comma-separated skill names
            List<Skill> matched = new ArrayList<>();
            String[] names = result.split(",");
            Map<String, Skill> candidateMap = new HashMap<>();
            for (Skill s : candidates) {
                candidateMap.put(s.getName(), s);
            }
            for (String name : names) {
                String trimmed = name.trim();
                Skill s = candidateMap.get(trimmed);
                if (s != null && !matched.contains(s)) {
                    matched.add(s);
                }
            }
            return matched;
        } catch (Exception e) {
            logger.warn("L2 semantic matching failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public String buildContext(List<Skill> skills) {
        return buildContext(skills, null);
    }

    /**
     * Build context with optional variable values for template rendering.
     * Variables are resolved with priority: explicit values > YAML defaults > empty.
     * Each skill body is truncated to MAX_SKILL_BODY_SIZE.
     */
    public String buildContext(List<Skill> skills, Map<String, String> variables) {
        if (skills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(NEWLINE).append(NEWLINE).append("## Activated Skills").append(NEWLINE).append(NEWLINE);
        for (Skill skill : skills) {
            sb.append("### [").append(skill.getName()).append("] ").append(skill.getDescription()).append(NEWLINE);
            // Include skill directory path so AI can resolve relative paths to scripts/assets
            Path dir = skill.getFilePath() != null ? skill.getFilePath().getParent() : null;
            if (dir != null) {
                sb.append("Skill directory: ").append(dir.toAbsolutePath().normalize()).append(NEWLINE);
            }
            // Render body with variable substitution
            String body = skill.getBody();
            if (body != null && !body.isEmpty()) {
                body = renderVariables(body, skill, variables);
                // Truncate oversized bodies
                if (body.length() > MAX_SKILL_BODY_SIZE) {
                    body = body.substring(0, MAX_SKILL_BODY_SIZE)
                            + "\n\n...(truncated, " + body.length() + " chars total)";
                }
            }
            sb.append(body != null ? body : "").append(NEWLINE).append(NEWLINE);
        }
        return sb.toString();
    }

    /**
     * Render {{variable}} placeholders in the body text.
     * Priority: explicit values > YAML defaults > empty string.
     */
    private String renderVariables(String body, Skill skill, Map<String, String> explicitVars) {
        if (body == null || body.isEmpty()) return body;
        Map<String, Object> varDefs = skill.getVariables();
        if (varDefs == null || varDefs.isEmpty()) {
            // No variable definitions, but still substitute any explicitly passed vars
            if (explicitVars != null) {
                for (Map.Entry<String, String> entry : explicitVars.entrySet()) {
                    body = body.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
                }
            }
            return body;
        }

        // Build a merged variable map: defaults first, then explicit overrides
        Map<String, String> resolved = new java.util.HashMap<>();
        for (Map.Entry<String, Object> varEntry : varDefs.entrySet()) {
            if (varEntry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> def = (Map<String, Object>) varEntry.getValue();
                Object defaultVal = def.get("default");
                resolved.put(varEntry.getKey(), defaultVal != null ? defaultVal.toString() : "");
            }
        }
        // Explicit values override defaults
        if (explicitVars != null) {
            for (Map.Entry<String, String> entry : explicitVars.entrySet()) {
                resolved.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
            }
        }

        // Replace placeholders
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            body = body.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return body;
    }

    /**
     * Create a skill in the project skills directory (highest priority)
     * If no project directory is set, falls back to global skills directory
     */
    public void createSkill(String name, String content) throws IOException {
        Path skillPath = getPrimarySkillsDir().resolve(name + ".skill.md");
        Files.write(skillPath, content.getBytes());
        reload();
    }

    /**
     * Update a skill - if it exists in a lower priority dir, it's updated there
     * If it exists in a higher priority dir, the update happens there
     */
    public void updateSkill(String name, String content) throws IOException {
        if (!skills.containsKey(name)) {
            throw new IOException("Skill not found: " + name);
        }
        Path skillPath = skills.get(name).getFilePath();
        Files.write(skillPath, content.getBytes());
        reload();
    }

    public String getSkillContent(String name) {
        Skill skill = skills.get(name);
        if (skill == null) return "Skill not found: " + name;

        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(skill.getName()).append(NEWLINE);
        sb.append("description: ").append(skill.getDescription()).append(NEWLINE);
        sb.append("version: ").append(skill.getVersion()).append(NEWLINE);
        sb.append("---").append(NEWLINE);
        sb.append(skill.getBody());
        return sb.toString();
    }


    /**
     * Get the primary skills directory for creating new skills (project dir if set, else global)
     */
    private Path getPrimarySkillsDir() {
        return projectSkillsDir != null && Files.exists(projectSkillsDir)
                ? projectSkillsDir
                : globalSkillsDir;
    }

    /**
     * Get all skills directories for debugging/display
     */
    public java.util.List<Path> getSkillsDirectories() {
        return getDirsInPriorityOrder();
    }

    public void reload() {
        this.skills = loadAllSkillsInto();
    }

    // Getters for paths
    public Path getGlobalSkillsDir() { return globalSkillsDir; }
    public Path getJarSkillsDir() { return jarSkillsDir; }
    public Path getProjectSkillsDir() { return projectSkillsDir; }

    /**
     * Get all loaded skills as a list in priority order.
     */
    public List<Skill> getSkills() {
        return new java.util.ArrayList<>(skills.values());
    }

    // ==================== Hot Reload (WatchService) ====================

    private Thread fileWatcherThread;
    private volatile boolean watching;

    /**
     * Start watching skill directories for changes and auto-reload.
     * Uses WatchService with 500ms debounce. Runs as a daemon thread.
     */
    public synchronized void startFileWatcher() {
        if (watching) return;
        watching = true;

        fileWatcherThread = new Thread(() -> {
            List<Path> dirs = getDirsInPriorityOrder();
            List<java.nio.file.WatchService> watchers = new ArrayList<>();

            // Register watchers for each directory that exists
            for (Path dir : dirs) {
                if (dir != null && Files.exists(dir)) {
                    try {
                        java.nio.file.WatchService watcher = FileSystems.getDefault().newWatchService();
                        dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                        watchers.add(watcher);
                    } catch (IOException e) {
                        logger.warn("Failed to start watcher for {}: {}", dir, e.getMessage());
                    }
                }
            }

            if (watchers.isEmpty()) {
                logger.debug("No skill directories to watch");
                watching = false;
                return;
            }

            while (watching) {
                for (java.nio.file.WatchService watcher : watchers) {
                    try {
                        WatchKey key = watcher.poll(500, TimeUnit.MILLISECONDS);
                        if (key != null) {
                            for (WatchEvent<?> event : key.pollEvents()) {
                                // Debounce: wait a short time for writes to complete
                                try { Thread.sleep(200); } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                                reload();
                                logger.info("Skills reloaded (detected: {})", event.kind().name());
                                break;
                            }
                            key.reset();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        logger.warn("Error in file watcher: {}", e.getMessage());
                    }
                }
            }

            // Close all watchers
            for (java.nio.file.WatchService watcher : watchers) {
                try { watcher.close(); } catch (IOException ignored) {}
            }
        }, "skill-file-watcher");

        fileWatcherThread.setDaemon(true);
        fileWatcherThread.start();
    }

    /**
     * Stop watching skill directories.
     */
    public synchronized void stopFileWatcher() {
        watching = false;
        if (fileWatcherThread != null) {
            fileWatcherThread.interrupt();
            fileWatcherThread = null;
        }
    }

    public boolean isWatching() { return watching; }

    // ==================== Template Creation ====================

    /**
     * Create a skill with auto-generated frontmatter template.
     * The body is set to a placeholder waiting for user edits.
     */
    public void createSkill(String name, String description, List<String> triggers) throws IOException {
        createSkill(name, description, triggers, "(waiting for user to edit body)");
    }

    /**
     * Create a skill with auto-generated frontmatter template and custom body.
     */
    public void createSkill(String name, String description, List<String> triggers, String body) throws IOException {
        StringBuilder content = new StringBuilder();
        content.append("---\n");
        content.append("name: ").append(name).append("\n");
        content.append("description: ").append(description).append("\n");
        content.append("version: 1.0.0\n");
        content.append("enabled: true\n");
        content.append("priority: 0\n");
        if (triggers != null && !triggers.isEmpty()) {
            content.append("triggers:\n");
            for (String t : triggers) {
                content.append("  - ").append(t).append("\n");
            }
        }
        content.append("---\n\n");
        content.append(body);
        createSkill(name, content.toString());
    }

    // ==================== Source Display ====================

    /**
     * List skills grouped by source directory.
     */
    public String listSkills() {
        return listSkills(false);
    }

    /**
     * List skills grouped by source directory, optionally including system skills.
     */
    public String listSkills(boolean showAll) {
        StringBuilder sb = new StringBuilder("Available Skills:").append(NEWLINE);

        // Group by source directory
        Map<String, List<Skill>> bySource = new LinkedHashMap<>();
        for (Skill skill : skills.values()) {
            if (!showAll && "system".equals(skill.getKind())) continue;
            String source = getSkillSource(skill);
            bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(skill);
        }

        for (Map.Entry<String, List<Skill>> entry : bySource.entrySet()) {
            sb.append("  -- ").append(entry.getKey()).append(" --").append(NEWLINE);
            for (Skill skill : entry.getValue()) {
                sb.append("    - ").append(skill.getName())
                  .append(" (v").append(skill.getVersion() != null ? skill.getVersion() : "?").append(")")
                  .append(skill.isEnabled() ? "" : " [disabled]")
                  .append(": ").append(skill.getDescription() != null ? skill.getDescription() : "")
                  .append(NEWLINE);
            }
        }

        return sb.toString();
    }

    /**
     * Determine the source label for a skill based on its file path.
     */
    private String getSkillSource(Skill skill) {
        Path filePath = skill.getFilePath();
        if (filePath == null) return "unknown";

        Path parent = filePath.getParent();
        if (parent == null) return "unknown";

        if (parent.equals(globalSkillsDir)) {
            return "Global (~/.diatom/skills/)";
        }
        if (jarSkillsDir != null && parent.equals(jarSkillsDir)) {
            return "JAR ({jar}/.diatom/skills/)";
        }
        if (projectSkillsDir != null && parent.equals(projectSkillsDir)) {
            return "Project (.diatom/skills/)";
        }
        return parent.toString();
    }

    // ==================== CLI Commands ====================

    /**
     * Enable a skill by name.
     * @return true if the skill was found and enabled
     */
    public boolean enableSkill(String name) {
        Skill skill = skills.get(name);
        if (skill == null) return false;
        skill.setEnabled(true);
        return true;
    }

    /**
     * Disable a skill by name.
     * @return true if the skill was found and disabled
     */
    public boolean disableSkill(String name) {
        Skill skill = skills.get(name);
        if (skill == null) return false;
        skill.setEnabled(false);
        return true;
    }

    /**
     * Get skills that have allowedTools restrictions defined.
     * Used by PermissionChecker to enforce tool isolation.
     */
    public List<Skill> getActiveSkillsWithRestrictions() {
        String currentProfile = this.activeProfile;
        List<Skill> result = new ArrayList<>();
        for (Skill skill : skills.values()) {
            if (!skill.isEnabled()) continue;
            // Profile filter (same logic as matchSkills)
            if (currentProfile != null && skill.getProfile() != null
                    && !currentProfile.equals(skill.getProfile())
                    && !"system".equals(skill.getKind())) {
                continue;
            }
            String allowedTools = skill.getAllowedTools();
            if (allowedTools != null && !allowedTools.trim().isEmpty()) {
                result.add(skill);
            }
        }
        return result;
    }
}
