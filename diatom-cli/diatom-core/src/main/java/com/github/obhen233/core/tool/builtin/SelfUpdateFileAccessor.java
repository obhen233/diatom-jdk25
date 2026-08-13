package com.github.obhen233.core.tool.builtin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Independent file accessor for SelfUpdateTools.
 * Provides isolated file operations for self-update functionality,
 * completely separated from the main workspace FileTools.
 * 
 * Key features:
 * 1. Dedicated sources directory (next to diatom-cli.jar in standalone mode, or in user.dir)
 * 2. No dependency on workspace validation
 * 3. Automatic sources/ prefix handling
 * 4. Security: blocks path traversal and absolute paths
 */
public class SelfUpdateFileAccessor {
    private static final Logger logger = LoggerFactory.getLogger(SelfUpdateFileAccessor.class);

    private final String sourcesDir;

    public SelfUpdateFileAccessor() {
        // Use the JAR directory (diatom-cli.jar's location) if running in standalone mode,
        // otherwise fall back to the current working directory.
        // This ensures sources live alongside the JAR, not in the CWD.
        String baseDir = System.getProperty("diatom.jar.dir", System.getProperty("user.dir"));
        // Use Paths.get to properly handle OS-specific path separators
        this.sourcesDir = Paths.get(baseDir, "sources").toString();
    }

    public SelfUpdateFileAccessor(String sourcesDir) {
        this.sourcesDir = sourcesDir;
    }

    /**
     * Get the sources directory path.
     */
    public String getSourcesDir() {
        return sourcesDir;
    }
    
    /**
     * Write a source file to the sources directory.
     * Automatically handles sources/ prefix - if path starts with "sources/", it's stripped.
     * 
     * @param relativePath Relative path within sources directory (e.g., "src/main/java/App.java" or "sources/src/main/java/App.java")
     * @param content File content
     * @return Result message
     */
    public String writeSourceFile(String relativePath, String content) throws IOException {
        // Normalize and validate path
        String normalizedPath = normalizeSourcePath(relativePath);

        // Auto-prepend src/main/java/ for .java files (Maven standard source directory layout).
        // AI often omits this prefix based on tool description examples, causing the file
        // to be written outside Maven's source root and silently excluded from compilation.
        if (normalizedPath.endsWith(".java") && !normalizedPath.startsWith("src/main/java/")) {
            normalizedPath = "src/main/java/" + normalizedPath;
            logger.debug("Auto-prepended src/main/java/ for Java source: {}", normalizedPath);
        }

        // Security check
        validateSourcePath(normalizedPath);

        // Sanitize content: strip read_file header "=== filename (lines X-Y of Z) ==="
        // and truncation marker "... (truncated at line X, total ~Y lines)"
        String sanitized = sanitizeReadFileHeader(content);

        Path targetPath = Paths.get(sourcesDir, normalizedPath);
        Path parentDir = targetPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        Files.write(targetPath, sanitized.getBytes(StandardCharsets.UTF_8));
        logger.info("Source file written: {}", normalizedPath);
        return "Source file written: " + normalizedPath;
    }
    
    /**
     * Strip read_file header/footer markers from content.
     * Models sometimes copy the "=== filename (lines X-Y of Z) ===" header
     * and "... (truncated...)" footer from read_file output into write_file calls.
     */
    private String sanitizeReadFileHeader(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // Strip leading header: "=== filename (lines X-Y of Z) ==="
        int firstNewline = content.indexOf('\n');
        if (firstNewline > 0) {
            String firstLine = content.substring(0, firstNewline).trim();
            if (firstLine.startsWith("=== ") && firstLine.contains("(lines ") && firstLine.endsWith(") ===")) {
                content = content.substring(firstNewline + 1);
            }
        }
        // Strip trailing truncation: "... (truncated at line X, total ~Y lines)"
        int lastNewline = content.lastIndexOf('\n');
        if (lastNewline >= 0) {
            String lastLine = content.substring(lastNewline + 1).trim();
            if (lastLine.startsWith("... (truncated at line ") && lastLine.contains("lines)")) {
                content = content.substring(0, lastNewline);
            }
        }
        return content;
    }

    /**
     * Read a source file from the sources directory.
     * Automatically handles sources/ prefix.
     * 
     * @param relativePath Relative path within sources directory
     * @return File content
     */
    public String readSourceFile(String relativePath) throws IOException {
        String normalizedPath = normalizeSourcePath(relativePath);
        validateSourcePath(normalizedPath);
        
        Path targetPath = Paths.get(sourcesDir, normalizedPath);
        if (!Files.exists(targetPath)) {
            throw new IOException("Source file not found: " + normalizedPath);
        }
        
        return new String(Files.readAllBytes(targetPath), StandardCharsets.UTF_8);
    }
    
    /**
     * Delete a source file from the sources directory.
     * 
     * @param relativePath Relative path within sources directory
     * @return true if deleted, false if not found
     */
    public boolean deleteSourceFile(String relativePath) throws IOException {
        String normalizedPath = normalizeSourcePath(relativePath);
        validateSourcePath(normalizedPath);
        
        Path targetPath = Paths.get(sourcesDir, normalizedPath);
        if (Files.exists(targetPath)) {
            Files.delete(targetPath);
            logger.info("Source file deleted: {}", normalizedPath);
            return true;
        }
        return false;
    }
    
    /**
     * Check if a source file exists.
     * 
     * @param relativePath Relative path within sources directory
     * @return true if exists
     */
    public boolean sourceFileExists(String relativePath) {
        String normalizedPath = normalizeSourcePath(relativePath);
        Path targetPath = Paths.get(sourcesDir, normalizedPath);
        return Files.exists(targetPath);
    }
    
    /**
     * Get the full path for a source file.
     * 
     * @param relativePath Relative path within sources directory
     * @return Full path
     */
    public Path getSourceFilePath(String relativePath) {
        String normalizedPath = normalizeSourcePath(relativePath);
        return Paths.get(sourcesDir, normalizedPath);
    }
    
    /**
     * Ensure the sources directory exists.
     */
    public void ensureSourcesDirExists() throws IOException {
        Path dir = Paths.get(sourcesDir);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            logger.info("Created sources directory: {}", sourcesDir);
        }
    }
    
    /**
     * Normalize source path by removing optional "sources/" prefix.
     * This handles cases where AI includes the sources/ prefix from get_source_tree output.
     * 
     * Examples:
     * - "sources/src/main/java/App.java" -> "src/main/java/App.java"
     * - "src/main/java/App.java" -> "src/main/java/App.java"
     * - "sources/com/github/Example.java" -> "com/github/Example.java"
     */
    public String normalizeSourcePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        
        // Normalize separators
        String normalized = path.replace('\\', '/');
        
        // Remove leading "sources/" prefix if present
        // This handles various formats:
        // - "sources/src/..." -> "src/..."
        // - "sources/com/..." -> "com/..."
        if (normalized.startsWith("sources/")) {
            normalized = normalized.substring("sources/".length());
            logger.debug("Stripped sources/ prefix: {} -> {}", path, normalized);
        }
        
        // Also handle "Sources/" with capital S (case-insensitive)
        if (normalized.toLowerCase().startsWith("sources/")) {
            normalized = normalized.substring("sources/".length());
            logger.debug("Stripped Sources/ prefix: {} -> {}", path, normalized);
        }
        
        return normalized;
    }
    
    /**
     * Validate that a path is safe for source file operations.
     * Blocks:
     * - Absolute paths (C:\, /)
     * - Path traversal (..)
     * - UNC paths (\\)
     */
    public void validateSourcePath(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new IOException("Path cannot be empty");
        }
        
        // Check for absolute paths
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IOException("Absolute paths not allowed. Use relative path like: src/main/java/App.java");
        }
        
        // Check for path traversal
        if (path.contains("..")) {
            throw new IOException("Path traversal not allowed: " + path);
        }
        
        // Check for UNC paths
        if (path.startsWith("\\\\")) {
            throw new IOException("UNC paths not allowed: " + path);
        }

        // Read-only protection for core-spi.json (core SPI metadata)
        if (path.equals("core-spi.json") || path.endsWith("/core-spi.json") || path.endsWith("\\core-spi.json")) {
            throw new IOException("Read-only area: " + path + " is core SPI metadata and cannot be modified.");
        }
        // Also block core-src/ (legacy read-only area)
        if (path.startsWith("core-src/") || path.startsWith("core-src\\")) {
            throw new IOException("Read-only area: " + path + " is a core source and cannot be modified.");
        }
    }
    
    /**
     * Replace content in a source file within the sources directory.
     * Automatically handles sources/ prefix.
     * Uses Apache Commons Text DiffUtils for intelligent whitespace-tolerant matching.
     *
     * @param relativePath Relative path within sources directory
     * @param oldStr The string to be replaced
     * @param newStr The replacement string
     * @return Result message
     */
    public String replaceSourceFile(String relativePath, String oldStr, String newStr) throws IOException {
        String normalizedPath = normalizeSourcePath(relativePath);
        validateSourcePath(normalizedPath);

        Path targetPath = Paths.get(sourcesDir, normalizedPath);
        if (!Files.exists(targetPath)) {
            String suggestion = "";
            // Check if it's a build file (not a source file)
            if (normalizedPath.endsWith(".xml") || normalizedPath.endsWith(".gradle") || normalizedPath.equals("pom.xml")) {
                suggestion = " NOTE: Build files (XML, gradle) are not typically modified via replace_source_in_file. Use write_source_file to overwrite the entire file content instead.";
                // Check if pom.xml exists at root level (common mistake)
                if (normalizedPath.contains("pom.xml") || normalizedPath.endsWith(".xml")) {
                    Path rootPom = Paths.get(sourcesDir, "pom.xml");
                    if (Files.exists(rootPom)) {
                        suggestion += " HINT: Found pom.xml at root level - did you mean 'pom.xml' instead of '" + normalizedPath + "'?";
                    }
                }
            } else {
                suggestion = " Use write_source_file to create the file with full content, or check if init_sources has been called.";
            }
            throw new IOException("Source file not found: " + normalizedPath + suggestion);
        }

        String content = new String(Files.readAllBytes(targetPath), StandardCharsets.UTF_8);

        // Strategy 1: Try exact match first (fastest)
        if (content.contains(oldStr)) {
            content = content.replace(oldStr, newStr);
            Files.write(targetPath, content.getBytes(StandardCharsets.UTF_8));
            logger.info("Source file replaced (exact match): {}", normalizedPath);
            return "Replacement done in: " + normalizedPath;
        }

        // Strategy 2: Use DiffUtils with Levenshtein distance for fuzzy matching
        String result = fuzzyReplace(content, oldStr, newStr);
        if (result != null) {
            Files.write(targetPath, result.getBytes(StandardCharsets.UTF_8));
            logger.info("Source file replaced (fuzzy match): {}", normalizedPath);
            return "Replacement done in: " + normalizedPath + " (whitespace-normalized match)";
        }

        // Strategy 3: Line-by-line matching (most robust for structural changes)
        result = lineBasedReplace(content, oldStr, newStr);
        if (result != null) {
            Files.write(targetPath, result.getBytes(StandardCharsets.UTF_8));
            logger.info("Source file replaced (line-based match): {}", normalizedPath);
            return "Replacement done in: " + normalizedPath + " (line-based match)";
        }

        // All strategies failed - provide helpful error
        String error = "String not found in file: " + normalizedPath + "\n";
        error += "The exact string to replace was not found. This often happens due to:\n";
        error += "1. Whitespace/indentation differences (tabs vs spaces)\n";
        error += "2. Line ending differences (CRLF vs LF)\n";
        error += "3. The file was already modified\n";
        error += "4. The target string spans multiple lines with structural differences\n\n";
        error += "Suggestion: Use read_source_file(path) to get the exact current content, ";
        error += "then use write_source_file(path, full_content) to overwrite with the new content.";
        throw new IOException(error);
    }

    /**
     * Fuzzy string replacement using Apache Commons Text StringsComparator.
     * Uses Levenshtein distance to find best matching substring.
     *
     * @param content Original file content
     * @param oldStr String to find
     * @param newStr Replacement string
     * @return Modified content, or null if no good match found
     */
    private String fuzzyReplace(String content, String oldStr, String newStr) {
        // Normalize both strings for comparison
        String normalizedContent = normalizeForDiff(content);
        String normalizedOld = normalizeForDiff(oldStr);

        // Calculate edit distance using our own Levenshtein implementation
        int editDistance = levenshteinDistance(normalizedContent, normalizedOld);

        // If similarity is high enough (edit distance < 20% of string length), find the best match
        int maxLen = Math.max(normalizedContent.length(), normalizedOld.length());
        if (editDistance < maxLen * 0.2 && editDistance < 200) {
            // Find the matching region and extract actual content from original
            String bestMatch = findBestMatchingSubstring(content, oldStr);
            if (bestMatch != null) {
                return content.replace(bestMatch, newStr);
            }
        }

        return null;
    }

    /**
     * Normalize string for diff comparison.
     * Handles tabs, multiple spaces, and line endings.
     */
    private String normalizeForDiff(String str) {
        // Replace CRLF with LF, collapse multiple spaces/tabs to single space
        return str
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("[ \\t]+", " ")
                .trim();
    }

    /**
     * Find the best matching substring in content that corresponds to oldStr.
     * Uses a combination of indexOf and character-by-character matching.
     */
    private String findBestMatchingSubstring(String content, String oldStr) {
        // Try to find start position using the first significant line
        String[] oldLines = oldStr.split("\n", -1);
        if (oldLines.length == 0) return null;

        // Find lines that have actual content (non-whitespace)
        List<String> significantLines = new ArrayList<>();
        for (String line : oldLines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                significantLines.add(trimmed);
            }
        }

        if (significantLines.isEmpty()) return null;

        // Find the first significant line in content
        String firstLine = significantLines.get(0);
        int searchStart = 0;
        while (searchStart < content.length()) {
            int idx = content.indexOf(firstLine, searchStart);
            if (idx == -1) break;

            // Try to match the full oldStr starting from this position
            String candidate = extractMatchingRegion(content, idx, oldStr);
            if (candidate != null) {
                // Verify similarity
                String normalizedCandidate = normalizeForDiff(candidate);
                String normalizedOld = normalizeForDiff(oldStr);
                if (normalizedCandidate.equals(normalizedOld) || computeSimilarity(normalizedCandidate, normalizedOld) > 0.85) {
                    return candidate;
                }
            }

            searchStart = idx + 1;
        }

        return null;
    }

    /**
     * Extract a region from content starting at given index that should match oldStr.
     * Uses length estimation based on the ratio of normalized to original strings.
     */
    private String extractMatchingRegion(String content, int startIdx, String oldStr) {
        // Estimate the length based on normalized vs original ratio
        double ratio = (double) oldStr.length() / Math.max(1, normalizeForDiff(oldStr).length());
        int estimatedLen = (int) (oldStr.length() * ratio);

        // Try a range of lengths around the estimate
        for (int len = Math.max(1, estimatedLen - 50); len <= estimatedLen + 100 && startIdx + len <= content.length(); len++) {
            String candidate = content.substring(startIdx, startIdx + len);
            String normalizedCandidate = normalizeForDiff(candidate);
            String normalizedOld = normalizeForDiff(oldStr);

            // If normalized strings match, use the original (unnormalized) version
            if (normalizedCandidate.equals(normalizedOld)) {
                return candidate;
            }
        }

        // Fallback: try to match character by character
        return matchCharacterByCharacter(content, startIdx, oldStr);
    }

    /**
     * Match strings character by character to find the best boundary.
     */
    private String matchCharacterByCharacter(String content, int startIdx, String target) {
        StringBuilder sb = new StringBuilder();
        int targetIdx = 0;
        int contentIdx = startIdx;

        while (targetIdx < target.length() && contentIdx < content.length()) {
            char targetChar = target.charAt(targetIdx);
            char contentChar = content.charAt(contentIdx);

            if (contentChar == targetChar) {
                sb.append(contentChar);
                targetIdx++;
                contentIdx++;
            } else if (Character.isWhitespace(contentChar) && contentIdx < content.length() - 1) {
                // Skip extra whitespace in content
                contentIdx++;
            } else if (Character.isWhitespace(targetChar)) {
                // Skip whitespace in target
                targetIdx++;
            } else {
                // Mismatch - try to recover
                break;
            }
        }

        // Verify we matched most of the target
        if ((double) targetIdx / target.length() > 0.9) {
            return sb.toString();
        }

        return null;
    }

    /**
     * Compute similarity ratio between two strings (0.0 to 1.0).
     */
    private double computeSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0;
        if (s1.isEmpty() && s2.isEmpty()) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0;

        // Use Levenshtein distance
        int maxLen = Math.max(s1.length(), s2.length());
        int distance = levenshteinDistance(s1, s2);
        return 1.0 - ((double) distance / maxLen);
    }

    /**
     * Calculate Levenshtein (edit) distance between two strings.
     */
    private int levenshteinDistance(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        int[][] dp = new int[len1 + 1][len2 + 1];

        for (int i = 0; i <= len1; i++) dp[i][0] = i;
        for (int j = 0; j <= len2; j++) dp[0][j] = j;

        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[len1][len2];
    }

    /**
     * Line-by-line based replacement for complex multi-line changes.
     * More robust for structural differences in code.
     */
    private String lineBasedReplace(String content, String oldStr, String newStr) {
        String[] contentLines = content.split("\n", -1);
        String[] oldLines = oldStr.split("\n", -1);
        String[] newLines = newStr.split("\n", -1);

        if (oldLines.length == 0 || contentLines.length == 0) return null;

        // Find matching line sequence
        int matchStart = -1;
        int matchEnd = -1;
        List<Integer> matchedIndices = new ArrayList<>();

        for (int i = 0; i <= contentLines.length - oldLines.length; i++) {
            matchedIndices.clear();
            boolean allMatch = true;

            for (int j = 0; j < oldLines.length; j++) {
                String contentLine = contentLines[i + j];
                String oldLine = oldLines[j];

                // Check if lines match (either exactly or with whitespace normalization)
                if (!contentLine.equals(oldLine) && !normalizeForDiff(contentLine).equals(normalizeForDiff(oldLine))) {
                    allMatch = false;
                    break;
                }
                matchedIndices.add(i + j);
            }

            if (allMatch) {
                matchStart = i;
                matchEnd = i + oldLines.length - 1;
                break;
            }
        }

        if (matchStart < 0) return null;

        // Build result: lines before match + new lines + lines after match
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < matchStart; i++) {
            result.append(contentLines[i]).append("\n");
        }

        // Add new lines
        for (int i = 0; i < newLines.length; i++) {
            result.append(newLines[i]);
            if (i < newLines.length - 1) result.append("\n");
        }

        // Add lines after match
        for (int i = matchEnd + 1; i < contentLines.length; i++) {
            result.append("\n").append(contentLines[i]);
        }

        // Remove trailing newline if original didn't have one
        if (!content.endsWith("\n") && result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
            result.deleteCharAt(result.length() - 1);
        }

        return result.toString();
    }

    /**
     * Check if a path is within the sources directory.
     * Used for security validation.
     */
    public boolean isWithinSourcesDir(Path path) {
        try {
            Path normalizedPath = path.toAbsolutePath().normalize();
            Path sourcesPath = Paths.get(sourcesDir).toAbsolutePath().normalize();
            return normalizedPath.startsWith(sourcesPath);
        } catch (Exception e) {
            return false;
        }
    }
}
