package com.github.obhen233.core.context;

import com.github.obhen233.core.database.ContextCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Provides project context (name, type, path, directory tree, build files)
 * for the AI system prompt. Scans project directory tree up to 3 levels
 * and reads key build files (pom.xml, build.gradle) — lightweight,
 * no full file indexing.
 */
public class ProjectIndexer {
    private static final Logger logger = LoggerFactory.getLogger(ProjectIndexer.class);

    public static final Map<String, String> PROJECT_TYPE_FILES = new HashMap<>();
    static {
        PROJECT_TYPE_FILES.put("pom.xml", "maven");
        PROJECT_TYPE_FILES.put("build.gradle", "gradle");
        PROJECT_TYPE_FILES.put("build.gradle.kts", "gradle");
        PROJECT_TYPE_FILES.put("package.json", "npm");
        PROJECT_TYPE_FILES.put("go.mod", "go");
        PROJECT_TYPE_FILES.put("Cargo.toml", "rust");
        PROJECT_TYPE_FILES.put("setup.py", "python");
        PROJECT_TYPE_FILES.put("requirements.txt", "python");
        PROJECT_TYPE_FILES.put("CMakeLists.txt", "cmake");
    }

    private Path workspaceDir;
    private volatile ProjectContext cachedContext;
    private ContextCacheManager contextCache;
    private long lastRefresh = 0;
    private static final long REFRESH_INTERVAL = 60_000;
    private static final int MAX_TREE_CHARS = 8000;

    public ProjectIndexer(String workspaceDir) {
        this.workspaceDir = Paths.get(workspaceDir);
    }

    /**
     * Dynamically set the project directory.
     * <p>
     * IDE mode: active project under workspace (e.g. D:\workspace\SQLExecutor).<br>
     * CLI mode: current working directory.
     * Invalidates the cached context so next getContext() re-scans.
     */
    public void setProjectDir(String path) {
        Path newDir = Paths.get(path);
        if (!newDir.equals(this.workspaceDir)) {
            this.workspaceDir = newDir;
            invalidate();
        }
    }

    public void setContextCache(ContextCacheManager contextCache) {
        this.contextCache = contextCache;
    }

    public ProjectContext getContext(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        if (cachedContext == null || forceRefresh || (now - lastRefresh) > REFRESH_INTERVAL) {
            cachedContext = createContext();
            lastRefresh = now;
        }
        return cachedContext;
    }

    public ProjectContext getContext() {
        return getContext(false);
    }

    private ProjectContext createContext() {
        ProjectContext ctx = new ProjectContext();
        ctx.setProjectPath(workspaceDir);
        ctx.setProjectName(workspaceDir.getFileName().toString());
        ctx.setProjectType(detectProjectType());

        // Lightweight directory tree scan (3 levels max)
        StringBuilder treeSb = new StringBuilder();
        collectFileTree(workspaceDir.toFile(), workspaceDir.toFile(), treeSb, 0, 3);
        // Cap tree output to prevent context bloat in huge projects
        if (treeSb.length() > MAX_TREE_CHARS) {
            treeSb.setLength(MAX_TREE_CHARS);
            treeSb.append("...\n(directory tree truncated at ").append(MAX_TREE_CHARS).append(" chars)\n");
        }
        ctx.setDirectoryTree(treeSb.toString());

        // Read key build files
        StringBuilder buildSb = new StringBuilder();
        appendBuildFileContent(workspaceDir.resolve("pom.xml"), buildSb);
        appendBuildFileContent(workspaceDir.resolve("build.gradle"), buildSb);
        appendBuildFileContent(workspaceDir.resolve("package.json"), buildSb);
        ctx.setBuildFileContent(buildSb.toString());

        // Persist context to database cache (workspace_context + project_context)
        if (contextCache != null) {
            try {
                Map<String, Object> contextData = new HashMap<>();
                contextData.put("name", ctx.getProjectName());
                contextData.put("type", ctx.getProjectType());
                contextData.put("tree", ctx.getDirectoryTree());
                contextData.put("build", ctx.getBuildFileContent());
                contextCache.saveContext(
                    ctx.getProjectPath().toString(),
                    ctx.getProjectName(),
                    ctx.getProjectType(),
                    contextData,
                    Collections.emptyMap()
                );
            } catch (Exception e) {
                logger.warn("Failed to save project context cache", e);
            }
        }

        return ctx;
    }

    private void collectFileTree(File root, File dir, StringBuilder sb, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) ->
            a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1) :
            a.getName().compareToIgnoreCase(b.getName()));
        String indent = "";
        for (int i = 0; i < depth; i++) indent += "  ";

        // Collapse lib/, node_modules/, dist/ directories to one-line summary
        String dirName = dir.getName();
        if (depth > 0 && ("lib".equals(dirName) || "node_modules".equals(dirName) || "dist".equals(dirName))) {
            int fileCount = 0;
            for (File f : files) {
                if (!f.isDirectory()) fileCount++;
            }
            sb.append(indent).append(dirName).append("/ (").append(fileCount).append(" files)\n");
            return;
        }

        int count = 0;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".") || name.equals("target") || name.equals("build") || name.equals("node_modules") || name.equals("dist"))
                continue;
            if (count++ >= 20) {
                sb.append(indent).append("  ...\n");
                break;
            }
            if (f.isDirectory()) {
                sb.append(indent).append(name).append("/\n");
                collectFileTree(root, f, sb, depth + 1, maxDepth);
            } else {
                sb.append(indent).append("  ").append(name).append("\n");
            }
        }
    }

    private void appendBuildFileContent(Path file, StringBuilder sb) {
        if (Files.exists(file)) {
            String fileName = file.getFileName().toString();
            sb.append(fileName).append(":\n```");
            if (fileName.endsWith(".xml")) sb.append("xml");
            else if (fileName.endsWith(".gradle")) sb.append("groovy");
            else if (fileName.endsWith(".json")) sb.append("json");
            sb.append("\n");
            try {
                byte[] bytes = Files.readAllBytes(file);
                String content = new String(bytes, StandardCharsets.UTF_8);
                if (content.length() > 4000) {
                    content = content.substring(0, 4000) + "\n... (truncated)";
                }
                sb.append(content).append("\n```\n\n");
            } catch (IOException e) {
                sb.append("[read failed]\n```\n\n");
            }
        }
    }

    private String detectProjectType() {
        for (Map.Entry<String, String> entry : PROJECT_TYPE_FILES.entrySet()) {
            if (Files.exists(workspaceDir.resolve(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return "unknown";
    }

    public void invalidate() {
        lastRefresh = 0;
        cachedContext = null;
    }
}
