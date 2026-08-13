package com.github.obhen233.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.spi.AutoApprovalStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import com.github.obhen233.util.JsonUtils;

/**
 * An {@link AutoApprovalStrategy} that reads whitelist rules from
 * {@code ~/.diatom/approval-whitelist.properties}.
 *
 * <p>Supports three types of whitelist:
 * <ul>
 *   <li>{@code danger.whitelist.tools} &mdash; comma-separated tool names to auto-approve
 *       for {@code [DANGER]}-classified operations</li>
 *   <li>{@code danger.whitelist.commands} &mdash; comma-separated command prefixes to
 *       auto-approve for {@code [DANGER]} operations (checked via {@code startsWith})</li>
 *   <li>{@code write.whitelist.paths} &mdash; comma-separated glob patterns for file paths
 *       to auto-approve {@code [WRITE]} operations outside the workspace</li>
 * </ul>
 *
 * <p>{@code ${user.dir}} is replaced with the current working directory
 * ({@code System.getProperty("user.dir")}) in path patterns.
 *
 * <p>Config is reloaded automatically when the file changes (checked every 5 seconds).
 * If the config file does not exist, this strategy always returns {@code ASK},
 * deferring to the built-in default strategy.
 *
 * <p>Priority: 50 (after custom SPI strategies, before built-in defaults).
 */
public class FileBasedWhitelistStrategy implements AutoApprovalStrategy {

    private static final Logger log = LoggerFactory.getLogger(FileBasedWhitelistStrategy.class);
    private static final ObjectMapper MAPPER = JsonUtils.getMapper();

    static final String CONFIG_FILE = System.getProperty("user.home")
        + File.separator + ".diatom" + File.separator + "approval-whitelist.properties";

    private static final long RELOAD_INTERVAL_MS = 5000L;

    // ---- cached config state ----
    private volatile long lastLoadTime = 0L;
    private long lastFileModified = 0L;

    private volatile Set<String> dangerTools = Collections.emptySet();
    private volatile List<String> dangerCommandPrefixes = Collections.emptyList();
    private volatile List<GlobPattern> writePathPatterns = Collections.emptyList();

    @Override
    public int getPriority() {
        return 50;
    }

    @Override
    public ApprovalDecision decide(ApprovalContext context) {
        // Whitelist does not apply in READ_ONLY mode
        if (context.getSandboxLevel() == SandboxLevel.READ_ONLY) {
            return ApprovalDecision.ASK;
        }

        String classification = context.getAiClassification();
        if (classification == null) {
            return ApprovalDecision.ASK;
        }

        String upper = classification.toUpperCase();

        reloadIfNeeded();

        if (upper.contains("[DANGER]")) {
            return decideDanger(context);
        }

        // Only apply write path whitelist when outside workspace
        if (upper.contains("[WRITE]") && context.isOutsideWorkspace()) {
            return decideWriteOutsideWorkspace(context);
        }

        return ApprovalDecision.ASK;
    }

    // ==================== Danger Whitelist ====================

    private ApprovalDecision decideDanger(ApprovalContext context) {
        // Check tool name whitelist
        String toolName = context.getToolName();
        if (toolName != null && dangerTools.contains(toolName)) {
            log.debug("DANGER whitelist: tool '{}' is whitelisted, approving", toolName);
            return ApprovalDecision.APPROVE;
        }

        // Check command prefix whitelist
        String cmd = extractCmdFromArgs(context.getArgsJson());
        if (cmd != null && !dangerCommandPrefixes.isEmpty()) {
            for (String prefix : dangerCommandPrefixes) {
                if (cmd.startsWith(prefix)) {
                    log.debug("DANGER whitelist: command '{}' matches prefix '{}', approving", cmd, prefix);
                    return ApprovalDecision.APPROVE;
                }
            }
        }

        return ApprovalDecision.ASK;
    }

    // ==================== Write Path Whitelist ====================

    private ApprovalDecision decideWriteOutsideWorkspace(ApprovalContext context) {
        if (writePathPatterns.isEmpty()) {
            return ApprovalDecision.ASK;
        }

        String path = extractPathFromArgs(context.getArgsJson());
        if (path == null) {
            return ApprovalDecision.ASK;
        }

        Path targetPath = Paths.get(path).normalize();
        for (GlobPattern pattern : writePathPatterns) {
            if (pattern.matcher.matches(targetPath)) {
                log.debug("Write whitelist: path '{}' matches pattern '{}', approving", path, pattern.raw);
                return ApprovalDecision.APPROVE;
            }
        }

        return ApprovalDecision.ASK;
    }

    // ==================== Config Loading ====================

    private void reloadIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastLoadTime < RELOAD_INTERVAL_MS) {
            return;
        }
        lastLoadTime = now;

        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            if (lastFileModified != 0L) {
                // File was deleted, reset to empty
                log.info("Whitelist config file deleted, clearing whitelist");
                clearWhitelist();
                lastFileModified = 0L;
            }
            return;
        }

        long modified = configFile.lastModified();
        if (modified == lastFileModified && !dangerTools.isEmpty()) {
            return; // No change
        }
        lastFileModified = modified;

        reloadConfig(configFile);
    }

    private void reloadConfig(File configFile) {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(configFile)) {
            props.load(in);
        } catch (Exception e) {
            log.warn("Failed to load whitelist config from {}, keeping previous rules: {}",
                configFile, e.getMessage());
            return;
        }

        // Parse danger tool names
        Set<String> newDangerTools = new HashSet<String>();
        String toolsVal = props.getProperty("danger.whitelist.tools", "").trim();
        if (!toolsVal.isEmpty()) {
            for (String t : toolsVal.split(",")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty()) {
                    newDangerTools.add(trimmed);
                }
            }
        }
        dangerTools = newDangerTools;

        // Parse danger command prefixes
        List<String> newDangerCommands = new ArrayList<String>();
        String cmdsVal = props.getProperty("danger.whitelist.commands", "").trim();
        if (!cmdsVal.isEmpty()) {
            for (String c : cmdsVal.split(",")) {
                String trimmed = c.trim();
                if (!trimmed.isEmpty()) {
                    newDangerCommands.add(trimmed);
                }
            }
        }
        dangerCommandPrefixes = newDangerCommands;

        // Parse write path glob patterns
        List<GlobPattern> newPathPatterns = new ArrayList<GlobPattern>();
        String pathsVal = props.getProperty("write.whitelist.paths", "").trim();
        if (!pathsVal.isEmpty()) {
            String userDir = System.getProperty("user.dir", "");
            for (String p : pathsVal.split(",")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    String resolved = trimmed.replace("${user.dir}", userDir);
                    newPathPatterns.add(new GlobPattern(trimmed, resolved));
                }
            }
        }
        writePathPatterns = newPathPatterns;

        log.info("Loaded whitelist config: {} danger tools, {} danger commands, {} write path patterns",
            newDangerTools.size(), newDangerCommands.size(), newPathPatterns.size());
    }

    private void clearWhitelist() {
        dangerTools = Collections.emptySet();
        dangerCommandPrefixes = Collections.emptyList();
        writePathPatterns = Collections.emptyList();
    }

    // ==================== JSON Argument Parsing ====================

    private static String extractPathFromArgs(String argsJson) {
        if (argsJson == null) return null;
        try {
            JsonNode node = MAPPER.readTree(argsJson);
            if (node.has("path")) {
                return node.get("path").asText();
            }
            if (node.has("filePath")) {
                return node.get("filePath").asText();
            }
        } catch (Exception e) {
            log.debug("Failed to extract path from argsJson: {}", e.getMessage());
        }
        return null;
    }

    private static String extractCmdFromArgs(String argsJson) {
        if (argsJson == null) return null;
        try {
            JsonNode node = MAPPER.readTree(argsJson);
            String cmd = node.has("cmd") ? node.get("cmd").asText() : "";
            String args = node.has("args") ? node.get("args").asText() : "";
            if (!cmd.isEmpty()) {
                return args.isEmpty() ? cmd : cmd + " " + args;
            }
        } catch (Exception e) {
            log.debug("Failed to extract command from argsJson: {}", e.getMessage());
        }
        return null;
    }

    // ==================== Glob Pattern Helper ====================

    static class GlobPattern {
        final String raw;
        final PathMatcher matcher;

        GlobPattern(String raw, String resolved) {
            this.raw = raw;
            // Convert to absolute path pattern if it looks like one
            String pattern = resolved;
            if (!pattern.startsWith("glob:") && !pattern.startsWith("regex:")) {
                pattern = "glob:" + pattern;
            }
            this.matcher = FileSystems.getDefault().getPathMatcher(pattern);
        }

        @Override
        public String toString() {
            return raw;
        }
    }
}
