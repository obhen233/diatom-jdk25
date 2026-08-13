package com.github.obhen233.core.skill;

import com.github.obhen233.util.I18n;
import com.github.obhen233.util.InstallPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


public class SystemPromptManager {
    private static final Logger logger = LoggerFactory.getLogger(SystemPromptManager.class);
    private static final Path PROMPT_FILE = InstallPaths.getInstallHome().resolve("system-prompt.skill.md");
    private static final Path SELF_UPDATE_SECTION_FILE = InstallPaths.getInstallHome().resolve("self-update-section.skill.md");

    /**
     * Detect if running in standalone JAR mode (self-update enabled)
     */
    private static boolean isStandaloneJarMode() {
        return "true".equals(System.getProperty("diatom.standalone.jar"));
    }


    private static final String BASE_PROMPT = """
            Diatom, a terminal coding assistant. \s
            Output directly. Avoid extended reasoning.
            Read/write files, run commands, manage skills.
            Be concise. No markdown.

            1. Discovery: get_source_tree FIRST (free). No list_files after.
            2. Paths: RELATIVE (src/App.java). Absolute (C:/, /) FORBIDDEN.
            3. Write: write_file (default), chunk (>10KB). Edit: replace_in_file.
            4. Complex tasks: break into 2-5 steps. Format 'Step N: [action] -> [expected]'.
               WAIT user confirmation before each.
            5. Destructive ops: confirm first. Errors: retry 3x -> alternatives.
            6. Batch: call ALL independent read-only tools (search_symbols, search_files, etc.) in a SINGLE step. For search_symbols, use | to batch multiple keyword variants. For search_files, batch glob patterns. NEVER search one keyword at a time — always batch.
               ★★★ SEARCH STRATEGY (MANDATORY) ★★★
               Step 1: ALWAYS call get_source_tree FIRST.
               Step 2: Read the "Detected Naming Patterns" section. Identify:
                 - common prefix (e.g. Zrml, Com, Sys)
                 - suffix convention (e.g. Controller, Service, Dao, Mapper)
                 - case style (PascalCase / camelCase / snake_case)
               Step 3: From the user request, extract business entities and actions:
                 - entities: 审批链(approval chain), 单据(document/bill), 专家黑名单(expert blacklist)
                 - actions: 新增(add), 选择(select), 撤销(revoke/cancel)
               Step 4: Generate CANDIDATE FILE NAMES by combining naming patterns + entities:
                 - Zrml + ApprovalChain + Controller -> "ZrmlApprovalChainController"
                 - Zrml + ExpertBlackList + Service -> "ZrmlExpertBlackListService"
               Step 5: Call search_symbols with FILENAME-ORIENTED keywords FIRST.
                 - Batch variants with |: "ZrmlApprovalChain|ZrmlExpertBlackList|ZrmlDocument|Revoke|Cancel"
                 - Use English/PascalCase/camelCase that match actual file names from the tree.
                 - AVOID generic Chinese words: 的, 了, 在, 是, 和, 时, 选择, 没有, 新增, 添加, 查询.
               Step 6: Only if filename search returns 0 results OR >50 results, fall back to content search with Chinese business terms: "审批链|专家黑名单|单据|撤销".
               ★★★ If Phase 1 filename results > 50 files, keywords are too broad. Do NOT proceed to Phase 2 content search. Refine keywords using project naming patterns.
            7. Cleanup: after task, call cleanup_workspace with generated files.
            8. diatom itself is a project. Modifying/Reading/Extracting/Analyzing diatom (including source code, jar packages, configurations, binaries) → NEED DEV mode.
               Self-update (including checking for updates,downloading updates,applying updates) is considered modification → REJECT if user not in DEV mode, prompt to enable DEV mode first.
               Work on other projects → NO DEV mode.

            Project Exploration (budget-free, use instead of direct read_file):
               search_symbols(query) -> locate relevant files by keyword. BATCH variants with |.
               summarize_file(path) -> preview file structure before reading
               search_references(symbol) -> check impact before modifying
               Flow: get_source_tree (observe naming patterns) → search_symbols (project-specific keywords) → summarize_file → read_file
               NOTE: get_source_tree already shows the full file tree. DO NOT call list_files/list_directory after it — you already have the structure!
               NOTE: list_directory only lists one directory. Prefer get_source_tree (one call, full tree) or search_files (glob pattern).

            Java: after write_file(src/**/*.java) -> run_command(mvn compile).
            Libs: search_maven -> add_lib.""";

    /**
     * Self-update tools section - only shown in development mode
     */
    private static final String SELF_UPDATE_SECTION = "\n" + """
            **Core:** read-only JAR. **Custom:** editable, extends core.
            **Workflow:** init_sources()->extract_sources()->edit->compile_sources()->restart_application.
            **Rule:** extend via custom, never modify core. No hot-swap.
            **No decompile:** NEVER use CFR/Procyon/JD-GUI/fernflower. To understand/modify diatom, use init_sources()+extract_sources() first.
            **SPI metadata:** read `sources/core-spi.json` before generating custom extension code.
            **Custom tools:** implement `com.github.obhen233.spi.ToolRegistrar`. In `registerTools(ToolRegistry registry)`, call `registry.scanObject(new YourTools())`. Tool methods must be annotated with `@ToolMethod` from `com.github.obhen233.core.tool.annotation.ToolMethod`.
            **Java 8 only:** avoid `Map.of`, `List.of`, `var`, text blocks, records, and switch expressions. Write JSON schemas as escaped Java string concatenation.
            **Dependencies:** for self-update dependencies, persist Maven dependencies in `sources/pom.xml`; `add_lib` alone is not enough unless the tool reports that it updated the source POM.
            **After compile_sources:** call `restart_application` immediately. Do NOT run extra verification commands unless explicitly asked.""";
    /**
     * Development mode flag - controls whether source modification tools are available
     */
    private static final String DEVELOPMENT_MODE_PROPERTY = "diatom.development_mode";

    /**
     * Check if running in development mode (allows modifying diatom source code)
     */
    public static boolean isDevelopmentMode() {
        return "true".equals(System.getProperty(DEVELOPMENT_MODE_PROPERTY));
    }

    /**
     * Enable development mode (allows modifying diatom source code)
     */
    public static void enableDevelopmentMode() {
        System.setProperty(DEVELOPMENT_MODE_PROPERTY, "true");
    }

    /**
     * Disable development mode (usage mode - default after restart from self-update)
     */
    public static void disableDevelopmentMode() {
        System.setProperty(DEVELOPMENT_MODE_PROPERTY, "false");
    }

    /**
     * Exit development mode and check for pending updates.
     * Called when user exits dev mode (via 'exit dev' or 'quit dev').
     * Returns a message about any pending updates.
     */
    public static String exitDevelopmentModeWithPendingCheck() {
        disableDevelopmentMode();

        StringBuilder sb = new StringBuilder();
        sb.append(I18n.get("dev_mode_disabled")).append("\n");

        // Check for pending updates
        try {
            Path appHome = InstallPaths.getInstallHome();
            Path corePendingMarker = appHome.resolve("core-update-pending.marker");
            Path customPendingMarker = appHome.resolve("custom").resolve("custom-update-pending.marker");

            if (Files.exists(corePendingMarker)) {
                String version = "";
                try {
                    version = new String(Files.readAllBytes(corePendingMarker), StandardCharsets.UTF_8).trim();
                } catch (IOException e) {}
                sb.append(I18n.get("self_update_core_pending_on_exit", version)).append("\n");
            }

            if (Files.exists(customPendingMarker)) {
                sb.append(I18n.get("self_update_custom_pending_on_exit")).append("\n");
            }

            if (!Files.exists(corePendingMarker) && !Files.exists(customPendingMarker)) {
                sb.append(I18n.get("self_update_no_pending_on_exit")).append("\n");
            }
        } catch (Exception e) {
            logger.warn("Failed to check pending updates on exit dev mode", e);
        }

        return sb.toString();
    }

    // Cache fields for system prompt file
    private volatile String cachedPrompt;
    private volatile long cachedFileModTime = -1;
    private volatile long cachedFileSize = -1;

    public SystemPromptManager() {
        ensurePromptFileExists();
        ensureSelfUpdateSectionFileExists();
    }

    private void ensureSelfUpdateSectionFileExists() {
        if (!Files.exists(SELF_UPDATE_SECTION_FILE)) {
            try {
                Files.createDirectories(SELF_UPDATE_SECTION_FILE.getParent());
                String defaultContent = "---\nname: self-update-section\ndescription: Development mode tools section for diatom self-update\nversion: 1.0.0\nkind: system\n---\n\n" + SELF_UPDATE_SECTION.substring(1); // SELF_UPDATE_SECTION starts with \n
                Files.write(SELF_UPDATE_SECTION_FILE, defaultContent.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                logger.error("Failed to create default self-update section", e);
            }
        }
    }

    private void ensurePromptFileExists() {
        if (!Files.exists(PROMPT_FILE)) {
            try {
                Files.createDirectories(PROMPT_FILE.getParent());
                String defaultContent = "---\nname: system-prompt\ndescription: AI assistant core behavior guidelines\nversion: 1.0.0\nkind: system\n---\n\n" + getSystemPrompt();
                Files.write(PROMPT_FILE, defaultContent.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                logger.error("Failed to create default system prompt", e);
            }
        }
    }

    /**
     * Get the system prompt, dynamically adjusted based on running mode.
     * Uses file-modification-time caching to avoid re-parsing unchanged files.
     * In development mode, includes self-update tools section for modifying diatom source.
     * In usage mode (default), only provides tools for user tasks.
     */
    public String getSystemPrompt() {
        boolean devMode = isDevelopmentMode() && isStandaloneJarMode();

        String prompt;

        // Priority: local .diatom/ (worker's own config) > install home default
        Path effectiveFile = resolveLocalPromptFile();
        if (effectiveFile == null || !Files.exists(effectiveFile)) {
            effectiveFile = PROMPT_FILE;
        }

        // Use cache if file hasn't changed (check mod time + size)
        if (Files.exists(effectiveFile)) {
            try {
                long modTime = Files.getLastModifiedTime(effectiveFile).toMillis();
                long fileSize = Files.size(effectiveFile);
                if (cachedPrompt != null && modTime == cachedFileModTime && fileSize == cachedFileSize) {
                    // Cache hit — use cached base prompt
                    prompt = cachedPrompt;
                } else {
                    // Cache miss — re-parse and cache
                    Skill skill = SkillParser.parse(effectiveFile);
                    prompt = skill.getBody();
                    cachedPrompt = prompt;
                    cachedFileModTime = modTime;
                    cachedFileSize = fileSize;
                }
            } catch (Exception e) {
                logger.warn("Failed to parse system prompt, using default", e);
                prompt = BASE_PROMPT;
            }
        } else {
            prompt = BASE_PROMPT;
        }

        // Add self-update section only in development mode (always append, not user customization)
        if (devMode) {
            String selfUpdateSection = loadSelfUpdateSection();
            prompt += selfUpdateSection;
        }

        return prompt;
    }

    /**
     * Resolve the local system-prompt.skill.md from the user's original working directory.
     * Allows each worker to have its own system prompt in {workerDir}/.diatom/system-prompt.skill.md,
     * while gateway/CLI mode continues to use the shared install-home path.
     */
    private Path resolveLocalPromptFile() {
        String userDir = System.getProperty("diatom.original.user.dir",
                System.getProperty("user.dir"));
        Path localFile = Paths.get(userDir, ".diatom", "system-prompt.skill.md");
        if (Files.exists(localFile) && !localFile.equals(PROMPT_FILE)) {
            return localFile;
        }
        return null;
    }

    /**
     * Load self-update section from user file, classpath, or use default
     */
    private String loadSelfUpdateSection() {
        // First: check user file in {jarDir}/.diatom/
        if (Files.exists(SELF_UPDATE_SECTION_FILE)) {
            try {
                Skill skill = SkillParser.parse(SELF_UPDATE_SECTION_FILE);
                String body = skill.getBody();
                if (isSelfUpdateSectionCurrent(body)) {
                    return "\n" + body;
                }
                logger.warn("Self-update section file is stale; using bundled/default guidance");
            } catch (Exception e) {
                logger.warn("Failed to parse self-update section from user file, trying classpath", e);
            }
        }

        // Second: check classpath resources
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("self-update-section.skill.md")) {
            if (in != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    boolean skipFrontmatter = false;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().equals("---") && !skipFrontmatter) {
                            skipFrontmatter = true;
                            continue;
                        }
                        if (skipFrontmatter) {
                            content.append(line).append("\n");
                        }
                    }
                    String result = content.toString().trim();
                    if (!result.isEmpty()) {
                        return "\n" + result;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load self-update section from classpath", e);
        }

        // Third: fallback to constant
        return SELF_UPDATE_SECTION;
    }

    static boolean isSelfUpdateSectionCurrent(String body) {
        if (body == null) {
            return false;
        }
        return body.contains("core-spi.json")
                && body.contains("scanObject")
                && body.contains("ToolMethod");
    }

    public void updatePrompt(String newBody) throws IOException {
        Path backup = PROMPT_FILE.resolveSibling("system-prompt.backup." + System.currentTimeMillis() + ".md");
        Files.copy(PROMPT_FILE, backup);

        String current = new String(Files.readAllBytes(PROMPT_FILE), StandardCharsets.UTF_8);
        String[] lines = current.split("\n");
        StringBuilder newContent = new StringBuilder();

        // Find end of frontmatter
        int frontmatterEnd = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("---") && i > 0) {
                frontmatterEnd = i;
                break;
            }
        }

        if (frontmatterEnd >= 0) {
            for (int i = 0; i <= frontmatterEnd; i++) {
                if (lines[i].startsWith("version:")) {
                    newContent.append("version: ").append(incrementVersion(extractVersion(lines[i]))).append("\n");
                } else {
                    newContent.append(lines[i]).append("\n");
                }
            }
            newContent.append("\n").append(newBody);
        } else {
            newContent.append("---\nname: system-prompt\ndescription: AI assistant core behavior guidelines\nversion: 1.0.1\nkind: system\n---\n\n").append(newBody);
        }

        Files.write(PROMPT_FILE, newContent.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void updateSelfUpdateSection(String newBody) throws IOException {
        Path backup = SELF_UPDATE_SECTION_FILE.resolveSibling("self-update-section.backup." + System.currentTimeMillis() + ".md");
        Files.copy(SELF_UPDATE_SECTION_FILE, backup);

        String current = new String(Files.readAllBytes(SELF_UPDATE_SECTION_FILE), StandardCharsets.UTF_8);
        String[] lines = current.split("\n");
        StringBuilder newContent = new StringBuilder();

        // Find end of frontmatter
        int frontmatterEnd = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("---") && i > 0) {
                frontmatterEnd = i;
                break;
            }
        }

        if (frontmatterEnd >= 0) {
            for (int i = 0; i <= frontmatterEnd; i++) {
                if (lines[i].startsWith("version:")) {
                    newContent.append("version: ").append(incrementVersion(extractVersion(lines[i]))).append("\n");
                } else {
                    newContent.append(lines[i]).append("\n");
                }
            }
            newContent.append("\n").append(newBody);
        } else {
            newContent.append("---\nname: self-update-section\ndescription: Development mode tools section for diatom self-update\nversion: 1.0.1\nkind: system\n---\n\n").append(newBody);
        }

        Files.write(SELF_UPDATE_SECTION_FILE, newContent.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String extractVersion(String line) {
        String[] parts = line.split(":");
        return parts.length > 1 ? parts[1].trim() : "1.0.0";
    }

    private String incrementVersion(String version) {
        String[] parts = version.split("\\.");
        if (parts.length >= 3) {
            int patch = Integer.parseInt(parts[2]) + 1;
            return parts[0] + "." + parts[1] + "." + patch;
        }
        return "1.0.1";
    }
}
