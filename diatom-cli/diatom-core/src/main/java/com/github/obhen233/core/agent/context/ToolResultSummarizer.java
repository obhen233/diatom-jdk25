package com.github.obhen233.core.agent.context;

import com.github.obhen233.core.database.SourceCodeExtensionsDao;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.obhen233.util.JsonUtils;

/**
 * Handles summarization and timestamp annotation for tool results.
 * 
 * Features:
 * 1. Summarizes information tool results (read_file, etc.) on second reference
 * 2. Adds timestamps to structure exploration tools (list_files, list_directory)
 * 3. Enables LLM to use cached results instead of re-sending full content
 */
public class ToolResultSummarizer {
    private static final Logger logger = LoggerFactory.getLogger(ToolResultSummarizer.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();
    
    // Tools that produce information content (should be summarized on second reference)
    private static final Set<String> INFORMATION_TOOLS = new HashSet<>(Arrays.asList(
        "read_file", "read_multiple_files", "cat", "head", "tail", "less", "more"
    ));
    
    // Tools for structure exploration (should have timestamps for caching)
    private static final Set<String> STRUCTURE_EXPLORATION_TOOLS = new HashSet<>(Arrays.asList(
        "list_files", "list_directory", "search_files", "glob", "find", "locate"
    ));
    
    // File read tools that need compression
    private static final Set<String> FILE_READ_TOOLS = new HashSet<>(Arrays.asList(
        "read_file", "read_multiple_files", "cat", "head", "tail"
    ));

    // Command output tools that need compression (tool names, not sub-commands)
    private static final Set<String> COMMAND_OUTPUT_TOOLS = new HashSet<>(Arrays.asList(
        "run_command", "compile_sources"
    ));
    
    // Source code file extensions - hardcoded fallback when database is not available
    private static final Set<String> FALLBACK_EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        ".java", ".kt", ".scala", ".py", ".js", ".ts", ".tsx", ".jsx", ".go", ".rs",
        ".c", ".cpp", ".cc", ".cxx", ".h", ".hpp", ".cs", ".rb", ".php", ".swift",
        ".vue", ".svelte", ".groovy", ".clj", ".ex", ".exs", ".erl", ".hs", ".ml",
        ".jsp", ".html", ".htm", ".xml", ".properties", ".tld", ".css", ".json",
        ".yaml", ".yml"
    )));

    // Dynamically loaded extensions from database (configurable)
    private volatile Set<String> dynamicExtensions = Collections.emptySet();

    // Optional DAO for loading extensions from database
    private SourceCodeExtensionsDao dao;

    public ToolResultSummarizer() {
        this(null);
    }

    public ToolResultSummarizer(SourceCodeExtensionsDao dao) {
        this.dao = dao;
        if (dao != null) {
            loadFromDatabase();
        }
    }

    /**
     * Set or update the SourceCodeExtensionsDao and reload extensions from database.
     */
    public void setSourceCodeExtensionsDao(SourceCodeExtensionsDao dao) {
        this.dao = dao;
        if (dao != null) {
            loadFromDatabase();
        }
    }

    private void loadFromDatabase() {
        try {
            List<SourceCodeExtensionsDao.SourceCodeExtension> extensions = dao.findEnabled();
            Set<String> exts = new HashSet<>();
            for (SourceCodeExtensionsDao.SourceCodeExtension ext : extensions) {
                exts.add(ext.extension.toLowerCase());
            }
            this.dynamicExtensions = exts;
            logger.info("Loaded {} source code extensions from database", exts.size());
        } catch (Exception e) {
            logger.warn("Failed to load source code extensions from database, using fallback", e);
        }
    }

    // Maximum summary length in characters
    private static final int MAX_SUMMARY_LENGTH = 500;
    
    // Maximum full content length before forcing summary
    private static final int MAX_FULL_CONTENT_LENGTH = 50000;
    
    // Threshold for file read result compression (characters)
    // Increased to allow more code context to pass through
    private static final int FILE_READ_COMPRESS_THRESHOLD = 3000;
    
    // Threshold for source code files - much larger to preserve full context
    private static final int SOURCE_CODE_COMPRESS_THRESHOLD = 10000;
    
    // Number of lines to keep when compressing file read results (increased for better code understanding)
    private static final int FILE_READ_KEEP_LINES = 50;
    
    // Maximum lines for Java files (need more context for class structure)
    private static final int FILE_READ_KEEP_LINES_JAVA = 150;

    // Maximum number of methods to extract when smart compressing Java files
    private static final int MAX_METHODS_TO_EXTRACT = 25;

    // Threshold for command output compression (characters)
    private static final int COMMAND_OUTPUT_COMPRESS_THRESHOLD = 5000;

    // Number of lines to keep when compressing command output (keep last N lines)
    private static final int COMMAND_OUTPUT_KEEP_LINES = 100;
    
    // Maximum cache entries to prevent unbounded memory growth in long-running server mode
    private static final int MAX_CACHE_SIZE = 500;

    // Cache of tool results with metadata
    // Key: cacheKey (toolName|argsHash), Value: ToolResultEntry
    private final Map<String, ToolResultEntry> resultCache = new ConcurrentHashMap<>();
    
    // Reference count per result (to track second+ references)
    private final Map<String, Integer> referenceCount = new ConcurrentHashMap<>();
    
    // Path to cache key mapping (for lookup by path)
    private final Map<String, Set<String>> pathToCacheKeys = new ConcurrentHashMap<>();
    
    /**
     * Represents a cached tool result with metadata
     */
    public static class ToolResultEntry {
        private final String toolName;
        private final String argsJson;
        private final String fullContent;
        private final String summary;
        private final String path;
        private final long timestamp;
        private final int contentSize;
        private final int referenceCount;
        
        public ToolResultEntry(String toolName, String argsJson, String fullContent, 
                              String summary, String path, long timestamp, 
                              int contentSize, int referenceCount) {
            this.toolName = toolName;
            this.argsJson = argsJson;
            this.fullContent = fullContent;
            this.summary = summary;
            this.path = path;
            this.timestamp = timestamp;
            this.contentSize = contentSize;
            this.referenceCount = referenceCount;
        }
        
        // Getters
        public String getToolName() { return toolName; }
        public String getArgsJson() { return argsJson; }
        public String getFullContent() { return fullContent; }
        public String getSummary() { return summary; }
        public String getPath() { return path; }
        public long getTimestamp() { return timestamp; }
        public int getContentSize() { return contentSize; }
        public int getReferenceCount() { return referenceCount; }
        
        public String getTimestampFormatted() {
            return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timestamp));
        }
    }
    
    /**
     * Process a tool result before sending to LLM.
     * - Adds timestamp annotation for structure exploration tools
     * - Returns summary for information tools on second+ reference
     * 
     * @param toolName Name of the tool
     * @param argsJson Tool arguments as JSON
     * @param result Tool result content
     * @return Processed result (with timestamp or summarized)
     */
    public String processResult(String toolName, String argsJson, String result) {
        if (result == null || result.isEmpty()) {
            return result;
        }
        
        String cacheKey = getCacheKey(toolName, argsJson);
        String path = extractPathFromArgs(argsJson);
        
        // Track reference count
        int refs = referenceCount.merge(cacheKey, 1, Integer::sum);
        
        // Store in cache
        String summary = generateSummary(toolName, result);
        ToolResultEntry entry = new ToolResultEntry(
            toolName, argsJson, result, summary, path, 
            System.currentTimeMillis(), result.length(), refs
        );
        resultCache.put(cacheKey, entry);

        // Evict oldest entries if cache exceeds max size
        if (resultCache.size() > MAX_CACHE_SIZE) {
            evictOldest();
        }

        // Index by path
        if (path != null) {
            pathToCacheKeys.computeIfAbsent(path, k -> ConcurrentHashMap.newKeySet()).add(cacheKey);
        }
        
        // Handle structure exploration tools - add timestamp
        if (isStructureExplorationTool(toolName)) {
            return addTimestampAnnotation(result, entry);
        }
        
        // Handle file read tools - compress large results on first reference
        // Use higher threshold for source code files to preserve context
        if (isFileReadTool(toolName)) {
            int compressThreshold = isSourceCodeFile(path) ? SOURCE_CODE_COMPRESS_THRESHOLD : FILE_READ_COMPRESS_THRESHOLD;

            if (result.length() > compressThreshold) {
                logger.info("Compressing file read result for {} (size: {} bytes, threshold: {})",
                    toolName, result.length(), compressThreshold);
                return compressFileReadResult(result);
            } else if (isSourceCodeFile(path) && result.length() > FILE_READ_COMPRESS_THRESHOLD) {
                // Source code file that's moderately large - log but don't compress
                logger.debug("Preserving full source code content for {} (size: {} bytes)",
                    path, result.length());
            }
        }

        // Handle command output - keep last N lines for large outputs
        // Errors and build summaries are typically at the end
        if (isCommandOutputTool(toolName) && result.length() > COMMAND_OUTPUT_COMPRESS_THRESHOLD) {
            logger.info("Compressing command output for {} (size: {} bytes, threshold: {})",
                toolName, result.length(), COMMAND_OUTPUT_COMPRESS_THRESHOLD);
            return compressCommandOutput(result);
        }
        
        // Handle information tools - return summary on second+ reference if content is large
        if (isInformationTool(toolName) && refs > 1) {
            if (result.length() > MAX_FULL_CONTENT_LENGTH) {
                logger.info("Returning summary for {} (refs: {}, size: {})", 
                    toolName, refs, result.length());
                return formatSummaryResponse(entry, refs);
            }
        }
        
        return result;
    }
    
    /**
     * Check if the file is a source code file based on extension.
     * Source code files need full context for understanding and modification.
     */
    private boolean isSourceCodeFile(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String lowerPath = path.toLowerCase();
        // First check dynamically loaded extensions from database
        for (String ext : dynamicExtensions) {
            if (lowerPath.endsWith(ext)) {
                return true;
            }
        }
        // Fall back to hardcoded extensions
        for (String ext : FALLBACK_EXTENSIONS) {
            if (lowerPath.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get cached result if available.
     * Returns summary on second+ reference for information tools.
     * 
     * @param toolName Name of the tool
     * @param argsJson Tool arguments as JSON
     * @return Cached result or null
     */
    public String getCachedResult(String toolName, String argsJson) {
        String cacheKey = getCacheKey(toolName, argsJson);
        ToolResultEntry entry = resultCache.get(cacheKey);
        
        if (entry == null) {
            return null;
        }
        
        int refs = referenceCount.merge(cacheKey, 1, Integer::sum);
        
        // For structure exploration tools, return timestamp-annotated cache indicator
        if (isStructureExplorationTool(toolName)) {
            return formatCachedStructureResult(entry);
        }
        
        // For information tools on second+ reference, return summary
        if (isInformationTool(toolName) && refs > 1 && entry.getFullContent().length() > MAX_FULL_CONTENT_LENGTH) {
            logger.info("Returning cached summary for {} (refs: {})", toolName, refs);
            return formatSummaryResponse(entry, refs);
        }
        
        return null; // Let the tool execute normally
    }
    
    /**
     * Check if we have a cached result for this tool call
     */
    public boolean hasCachedResult(String toolName, String argsJson) {
        return resultCache.containsKey(getCacheKey(toolName, argsJson));
    }
    
    /**
     * Get the reference count for a tool call
     */
    public int getReferenceCount(String toolName, String argsJson) {
        return referenceCount.getOrDefault(getCacheKey(toolName, argsJson), 0);
    }
    
    /**
     * Invalidate cache for a specific path
     */
    public void invalidateForPath(String path) {
        if (path == null) return;
        
        Set<String> keys = pathToCacheKeys.remove(path);
        if (keys != null) {
            for (String key : keys) {
                resultCache.remove(key);
                referenceCount.remove(key);
            }
            logger.debug("Invalidated {} cache entries for path: {}", keys.size(), path);
        }
    }
    
    /**
     * Clear all cached results
     */
    public void clear() {
        resultCache.clear();
        referenceCount.clear();
        pathToCacheKeys.clear();
        logger.debug("Cleared all tool result cache");
    }

    /**
     * Evict the oldest 20% of cache entries when max size is exceeded.
     * Prevents unbounded memory growth in long-running server mode.
     */
    private void evictOldest() {
        int evictCount = MAX_CACHE_SIZE / 5; // 100 entries
        if (resultCache.size() <= MAX_CACHE_SIZE) return;

        List<Map.Entry<String, ToolResultEntry>> sorted = new ArrayList<>(resultCache.entrySet());
        Collections.sort(sorted, (a, b) -> Long.compare(a.getValue().getTimestamp(), b.getValue().getTimestamp()));
        int removed = 0;
        for (int i = 0; i < evictCount && i < sorted.size(); i++) {
            String key = sorted.get(i).getKey();
            ToolResultEntry entry = resultCache.remove(key);
            if (entry != null) {
                referenceCount.remove(key);
                if (entry.getPath() != null) {
                    Set<String> keys = pathToCacheKeys.get(entry.getPath());
                    if (keys != null) {
                        keys.remove(key);
                        if (keys.isEmpty()) pathToCacheKeys.remove(entry.getPath());
                    }
                }
                removed++;
            }
        }
        logger.debug("Evicted {} oldest cache entries (size: {})", removed, resultCache.size());
    }
    
    /**
     * Get cache statistics
     */
    public CacheStats getStats() {
        return new CacheStats(
            resultCache.size(), 
            referenceCount.values().stream().mapToInt(Integer::intValue).sum(),
            pathToCacheKeys.size()
        );
    }
    
    // ============== Private Methods ==============
    
    private String getCacheKey(String toolName, String argsJson) {
        // Use actual argsJson string instead of hashCode to avoid collision risk
        return toolName + "|" + (argsJson != null ? argsJson : "");
    }
    
    private boolean isInformationTool(String toolName) {
        return INFORMATION_TOOLS.contains(toolName);
    }
    
    private boolean isStructureExplorationTool(String toolName) {
        return STRUCTURE_EXPLORATION_TOOLS.contains(toolName);
    }
    
    /**
     * Check if this is a file read tool
     */
    private boolean isFileReadTool(String toolName) {
        return FILE_READ_TOOLS.contains(toolName);
    }

    /**
     * Check if this is a command output tool
     */
    private boolean isCommandOutputTool(String toolName) {
        return COMMAND_OUTPUT_TOOLS.contains(toolName);
    }

    /**
     * Compress command output by filtering out noise and keeping signal-rich regions.
     *
     * Strategy: instead of hardcoding error keywords (fragile, tool-specific),
     * filter out lines matching universal noise patterns (INFO, progress, downloads).
     * Signal lines (errors, warnings, results) naturally survive the filter.
     *
     * Three zones:
     *   Header: first 5 lines — always keep (command, tool header)
     *   Body: middle section — noise-filtered, densely packed signal
     *   Tail: last 50 lines — always keep (build result, stack trace, summary)
     */
    private String compressCommandOutput(String result) {
        if (result == null || result.isEmpty()) {
            return result;
        }

        String[] lines = result.split("\\n", -1);
        int lineCount = lines.length;
        int byteCount = result.length();

        // Small outputs: keep as-is
        if (lineCount <= 80) {
            return result;
        }

        // Noise patterns — universal across build tools (Maven, Gradle, npm, etc.)
        // Lines matching NOTHING that an AI debugger cares about
        Pattern[] noisePatterns = {
            Pattern.compile("^\\[INFO\\]"),          // Maven INFO
            Pattern.compile("^\\[debug\\]"),          // Maven debug
            Pattern.compile("^Download(ing|ed) "),    // Dependency downloads
            Pattern.compile("^Progress "),            // Generic progress
            Pattern.compile("^\u00a0{4,}"),            // Indentation-only lines
            Pattern.compile("^\\s*$"),                 // Empty lines
        };

        // Line count thresholds
        int headerLines = 5;
        int tailLines = 50;
        int minSignalLines = 10; // Minimum signal lines to keep from body

        StringBuilder compressed = new StringBuilder();

        // Zone 1: Header — always keep
        int headerEnd = Math.min(headerLines, lineCount);
        for (int i = 0; i < headerEnd; i++) {
            compressed.append(lines[i]).append("\\n");
        }

        // Zone 2: Body — filter noise, detect signal transitions
        int bodyStart = headerEnd;
        int tailStart = Math.max(bodyStart, lineCount - tailLines);

        // Collect signal lines from body (non-noise lines)
        List<String> bodySignalLines = new ArrayList<>();
        boolean foundSignal = false;
        int firstSignalLine = -1;

        for (int i = bodyStart; i < tailStart; i++) {
            String line = lines[i];
            boolean isNoise = false;
            for (Pattern p : noisePatterns) {
                if (p.matcher(line).find()) {
                    isNoise = true;
                    break;
                }
            }
            if (!isNoise) {
                if (!foundSignal) {
                    foundSignal = true;
                    firstSignalLine = i;
                }
                bodySignalLines.add(line);
            }
        }

        if (foundSignal && !bodySignalLines.isEmpty()) {
            // Signal found in body — detect and annotate transition points
            int skippedBeforeSignal = firstSignalLine - bodyStart;
            if (skippedBeforeSignal > 5) {
                compressed.append(String.format(
                    "\\n... [%d lines of build progress suppressed] ...\\n\\n",
                    skippedBeforeSignal
                ));
            } else if (skippedBeforeSignal > 0) {
                // Small gap — just show the lines
                for (int i = bodyStart; i < firstSignalLine; i++) {
                    compressed.append(lines[i]).append("\\n");
                }
            }

            // Append signal lines
            int keptSignalLines = 0;
            for (String signalLine : bodySignalLines) {
                compressed.append(signalLine).append("\\n");
                keptSignalLines++;
            }

            int totalBodyLines = tailStart - bodyStart;
            int noiseSkipped = totalBodyLines - bodySignalLines.size();
            if (noiseSkipped > 0) {
                compressed.append(String.format(
                    "\\n... [%d noise lines (INFO/progress) filtered from middle section] ...\\n\\n",
                    noiseSkipped
                ));
            }
        } else {
            // No signal in body — just note the suppression
            int totalBodyLines = tailStart - bodyStart;
            if (totalBodyLines > 0) {
                compressed.append(String.format(
                    "\\n... [%d lines of standard output suppressed] ...\\n\\n",
                    totalBodyLines
                ));
            }
        }

        // Zone 3: Tail — always keep (build result, errors, summary)
        for (int i = tailStart; i < lineCount; i++) {
            compressed.append(lines[i]).append("\\n");
        }

        // Summary
        int keptLines = compressed.toString().split("\\n", -1).length;
        int skippedLines = lineCount - keptLines;
        compressed.append(String.format(
            "\\n[Command output compressed: %d / %d lines kept, %d filtered]",
            keptLines, lineCount, skippedLines
        ));

        String compressedResult = compressed.toString();
        logger.debug("Compressed command output: {} bytes -> {} bytes ({} -> {} lines, bodySignal={})",
            byteCount, compressedResult.length(), lineCount, keptLines, bodySignalLines.size());

        return compressedResult;
    }
    
    /**
     * Compress file read result by keeping only first N lines + file statistics.
     * This reduces tool result from ~2000 tokens to ~300 tokens.
     * 
     * For Java files, uses smart extraction to preserve class structure and method signatures.
     * 
     * @param result The full file content
     * @return Compressed result with first lines and statistics
     */
    private String compressFileReadResult(String result) {
        if (result == null || result.isEmpty()) {
            return result;
        }
        
        String[] lines = result.split("\\n", -1);
        int lineCount = lines.length;
        int byteCount = result.length();
        
        // Detect if this is a Java file
        boolean isJavaFile = isJavaContent(result, lines);
        int keepLines = isJavaFile ? FILE_READ_KEEP_LINES_JAVA : FILE_READ_KEEP_LINES;
        
        // For Java files, use smart extraction
        if (isJavaFile && lineCount > keepLines) {
            return compressJavaFileResult(result, lines, lineCount, byteCount);
        }
        
        // Standard compression: keep first N lines
        StringBuilder compressed = new StringBuilder();
        int actualKeepLines = Math.min(keepLines, lineCount);
        
        for (int i = 0; i < actualKeepLines; i++) {
            compressed.append(lines[i]);
            if (i < actualKeepLines - 1) {
                compressed.append("\\n");
            }
        }
        
        // Add file statistics
        compressed.append(String.format(
            "\\n\\n... (%d lines, %d bytes)" +
            "\\n[Tip: File content compressed, showing first %d lines only]",
            lineCount, byteCount, actualKeepLines
        ));
        
        String compressedResult = compressed.toString();
        logger.debug("Compressed file result: {} bytes -> {} bytes ({} lines total)", 
            byteCount, compressedResult.length(), lineCount);
        
        return compressedResult;
    }
    
    /**
     * Check if content is a Java source file
     */
    private boolean isJavaContent(String content, String[] lines) {
        if (lines == null || lines.length == 0) return false;
        
        // Check first 10 lines for Java indicators
        int checkLines = Math.min(10, lines.length);
        for (int i = 0; i < checkLines; i++) {
            String line = lines[i].trim();
            if (line.startsWith("package ") ||
                line.startsWith("import ") ||
                line.contains("public class ") ||
                line.contains("class ") ||
                line.contains("interface ") ||
                line.contains("@interface ") ||
                line.contains("enum ")) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Smart compression for Java files - preserves class structure and method signatures.
     */
    private String compressJavaFileResult(String content, String[] lines, int lineCount, int byteCount) {
        StringBuilder compressed = new StringBuilder();
        
        // Section 1: Package and imports (usually first 10-20 lines)
        int importEndLine = 0;
        for (int i = 0; i < Math.min(30, lines.length); i++) {
            String line = lines[i].trim();
            if (line.startsWith("package ") || line.startsWith("import ") ||
                line.isEmpty() || line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) {
                compressed.append(lines[i]).append("\\n");
                importEndLine = i + 1;
            } else if (!line.startsWith("import")) {
                break;
            }
        }
        
        // Add separator
        compressed.append("\\n// ===== CLASS STRUCTURE =====\\n\\n");
        
        // Section 2: Extract class definition and method signatures
        int methodsExtracted = 0;
        boolean inClass = false;
        int classStartLine = 0;
        
        for (int i = importEndLine; i < lines.length && methodsExtracted < MAX_METHODS_TO_EXTRACT; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            
            // Detect class/interface/enum definition
            if (isClassDefinition(trimmed)) {
                compressed.append(lines[i]).append("\\n");
                classStartLine = i;
                inClass = true;
                continue;
            }
            
            // Detect method signatures
            if (inClass && isMethodSignature(trimmed)) {
                compressed.append("  ").append(trimmed).append("\\n");
                methodsExtracted++;
            }
            
            // Also keep field declarations with their types
            if (inClass && isFieldDeclaration(trimmed)) {
                compressed.append("  ").append(trimmed).append("\\n");
            }
        }
        
        // Add file statistics
        compressed.append(String.format(
            "\\n\\n// ===== FILE STATISTICS =====" +
            "\\n// Total: %d lines, %d bytes" +
            "\\n// Methods extracted: %d" +
            "\\n[Tip: Java file smart-compressed, class structure and method signatures preserved]",
            lineCount, byteCount, methodsExtracted
        ));
        
        String compressedResult = compressed.toString();
        logger.debug("Smart-compressed Java file: {} bytes -> {} bytes ({} lines, {} methods)",
            byteCount, compressedResult.length(), lineCount, methodsExtracted);
        
        return compressedResult;
    }
    
    /**
     * Check if line is a class/interface/enum definition
     */
    private boolean isClassDefinition(String line) {
        if (line.isEmpty() || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
            return false;
        }
        return line.contains("class ") || line.contains("interface ") ||
               line.contains("enum ") || line.contains("@interface ");
    }
    
    /**
     * Check if line is a method signature (simplified heuristic)
     */
    private boolean isMethodSignature(String line) {
        // Skip comments, annotations, and empty lines
        if (line.isEmpty() || line.startsWith("//") || line.startsWith("*") ||
            line.startsWith("/*") || line.startsWith("@")) {
            return false;
        }
        
        // Method signature pattern: visibility + [static/final/abstract] + returnType + methodName(
        // Simplified: look for pattern like "public void method(" or "private String method("
        if (line.contains("(") && line.contains(")") && !line.contains("=")) {
            // Check for visibility modifiers
            if (line.startsWith("public ") || line.startsWith("private ") ||
                line.startsWith("protected ") || line.startsWith("static ") ||
                line.matches("^[a-zA-Z<>]+\\s+[a-zA-Z]+\\s*\\(.*")) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if line is a field declaration
     */
    private boolean isFieldDeclaration(String line) {
        if (line.isEmpty() || line.startsWith("//") || line.startsWith("*") ||
            line.startsWith("/*") || line.startsWith("@")) {
            return false;
        }
        
        // Field pattern: visibility + type + name + [= value];
        if (line.contains(";") && !line.contains("(") &&
            (line.startsWith("public ") || line.startsWith("private ") ||
             line.startsWith("protected ") || line.startsWith("static "))) {
            return true;
        }
        return false;
    }
    
    /**
     * Extract first N lines from content
     */
    private String extractFirstLines(String content, int maxLines) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        
        String[] lines = content.split("\\n", maxLines + 1);
        StringBuilder result = new StringBuilder();
        
        int keepLines = Math.min(maxLines, lines.length);
        for (int i = 0; i < keepLines; i++) {
            result.append(lines[i]);
            if (i < keepLines - 1) {
                result.append("\\n");
            }
        }
        
        return result.toString();
    }
    
    /**
     * Add timestamp annotation to structure exploration tool results
     */
    private String addTimestampAnnotation(String result, ToolResultEntry entry) {
        String timestamp = entry.getTimestampFormatted();
        String cacheHint = String.format(
            "\\n\\n[CACHE_INFO: timestamp=%s, tool=%s, path=%s]\\n" +
            "[Tip: This result has a timestamp, LLM can use cache to avoid re-sending this content]",
            timestamp, entry.getToolName(), entry.getPath() != null ? entry.getPath() : "N/A"
        );
        return result + cacheHint;
    }
    
    /**
     * Format a cached structure result (for cache hit)
     */
    private String formatCachedStructureResult(ToolResultEntry entry) {
        return String.format(
            "[CACHED_RESULT: Directory structure already explored, timestamp: %s]\\n" +
            "Path: %s\\n" +
            "Content size: %d bytes\\n" +
            "Reference count: %d\\n" +
            "To see full content, please specify which file or directory you need.",
            entry.getTimestampFormatted(),
            entry.getPath() != null ? entry.getPath() : "N/A",
            entry.getContentSize(),
            entry.getReferenceCount()
        );
    }
    
    /**
     * Generate a summary of the tool result
     */
    private String generateSummary(String toolName, String content) {
        if (content == null || content.isEmpty()) {
            return "(empty result)";
        }
        
        // For file content, extract key information
        if (isInformationTool(toolName)) {
            return generateContentSummary(content);
        }
        
        // For directory listings, summarize structure
        if (isStructureExplorationTool(toolName)) {
            return generateStructureSummary(content);
        }
        
        // Default: truncate
        if (content.length() > MAX_SUMMARY_LENGTH) {
            return content.substring(0, MAX_SUMMARY_LENGTH) + "\\n... [truncated, total " + content.length() + " chars]";
        }
        return content;
    }
    
    /**
     * Generate summary for file content
     */
    private String generateContentSummary(String content) {
        StringBuilder summary = new StringBuilder();
        
        // Count lines
        int lineCount = content.split("\\n", -1).length;
        summary.append("Total lines: ").append(lineCount).append("\\n");
        
        // Try to detect file type and extract key elements
        String fileType = detectFileType(content);
        if (fileType != null) {
            summary.append("File type: ").append(fileType).append("\\n");
        }
        
        // Extract class/function/method names for code files
        if (fileType != null && (fileType.contains("Java") || fileType.contains("Python") || 
            fileType.contains("JavaScript") || fileType.contains("TypeScript"))) {
            String symbols = extractCodeSymbols(content);
            if (symbols != null && !symbols.isEmpty()) {
                summary.append("\\nKey symbols:\\n").append(symbols).append("\\n");
            }
        }
        
        // Add preview (first few lines)
        String[] lines = content.split("\\n", 6);
        if (lines.length > 0) {
            summary.append("\\nContent preview:\\n");
            int previewLines = Math.min(5, lines.length);
            for (int i = 0; i < previewLines; i++) {
                summary.append(String.format("%4d: %s\\n", i + 1, lines[i]));
            }
            if (lines.length > 5) {
                summary.append("... (").append(lineCount - 5).append(" more lines)\\n");
            }
        }
        
        String result = summary.toString();
        if (result.length() > MAX_SUMMARY_LENGTH) {
            return result.substring(0, MAX_SUMMARY_LENGTH) + "\\n... [summary truncated]";
        }
        return result;
    }
    
    /**
     * Generate summary for directory listing
     */
    private String generateStructureSummary(String content) {
        StringBuilder summary = new StringBuilder();
        
        // Count files and directories
        String[] lines = content.split("\\n");
        int fileCount = 0;
        int dirCount = 0;
        Set<String> extensions = new TreeSet<>();
        
        for (String line : lines) {
            if (line.contains("<file ") || line.matches(".*^-.*\\s+\\d+\\s+.*")) {
                fileCount++;
                // Try to extract extension
                int dotIdx = line.lastIndexOf('.');
                if (dotIdx > 0 && dotIdx < line.length() - 1) {
                    String ext = line.substring(dotIdx).split("[\\s<]")[0];
                    if (ext.length() <= 10) { // Sanity check
                        extensions.add(ext);
                    }
                }
            } else if (line.contains("<folder ") || line.contains("<DIR>") || line.startsWith("d")) {
                dirCount++;
            }
        }
        
        summary.append("Directory structure summary:\\n");
        summary.append("- File count: ").append(fileCount).append("\\n");
        summary.append("- Directory count: ").append(dirCount).append("\\n");
        
        if (!extensions.isEmpty()) {
            summary.append("- File types: ").append(String.join(", ", extensions)).append("\\n");
        }
        
        // Add first few lines as preview
        summary.append("\\nFirst lines preview:\\n");
        int previewLines = Math.min(10, lines.length);
        for (int i = 0; i < previewLines; i++) {
            if (!lines[i].isEmpty()) {
                summary.append(lines[i]).append("\\n");
            }
        }
        if (lines.length > 10) {
            summary.append("... (").append(lines.length - 10).append(" more entries)\\n");
        }
        
        return summary.toString();
    }
    
    /**
     * Format summary response for LLM
     */
    private String formatSummaryResponse(ToolResultEntry entry, int refs) {
        return String.format(
            "[CONTENT_SUMMARY: This file was already read, reference #%d]\\n" +
            "Path: %s\\n" +
            "Size: %d bytes\\n" +
            "\\n%s\\n" +
            "\\n[To see full content, please specify line range or keywords you need]",
            refs,
            entry.getPath() != null ? entry.getPath() : "N/A",
            entry.getContentSize(),
            entry.getSummary()
        );
    }
    
    /**
     * Detect file type from content
     */
    private String detectFileType(String content) {
        if (content == null || content.isEmpty()) return null;
        
        String firstLine = content.split("\\n")[0];
        
        if (firstLine.startsWith("<?xml") || firstLine.startsWith("<")) return "XML/HTML";
        if (firstLine.startsWith("#!/")) {
            if (firstLine.contains("python")) return "Python";
            if (firstLine.contains("bash") || firstLine.contains("sh")) return "Shell";
            if (firstLine.contains("node")) return "JavaScript";
        }
        if (firstLine.contains("package ")) return "Java";
        if (firstLine.startsWith("import ") || firstLine.startsWith("from ")) {
            if (firstLine.contains("from ") && firstLine.contains(" import ")) return "Python";
            return "Python/JavaScript/TypeScript";
        }
        if (content.contains("function ") || content.contains("const ") || content.contains("let ")) {
            if (content.contains(": ") && (content.contains("interface ") || content.contains(": string") || content.contains(": number"))) {
                return "TypeScript";
            }
            return "JavaScript";
        }
        if (content.contains("class ") && content.contains("{")) {
            if (content.contains("public ") || content.contains("private ") || content.contains("void ")) {
                return "Java";
            }
        }
        if (content.startsWith("{") || content.startsWith("[")) return "JSON";
        if (content.contains("---") && content.contains(":")) return "YAML";
        
        return null;
    }
    
    /**
     * Extract code symbols (classes, functions, methods)
     */
    private String extractCodeSymbols(String content) {
        StringBuilder symbols = new StringBuilder();
        
        // Java: class, interface, enum, method definitions
        Pattern javaClassPattern = Pattern.compile("(?:public|private|protected)?\\s*(?:abstract\\s+)?(?:class|interface|enum)\\s+(\\w+)");
        Pattern javaMethodPattern = Pattern.compile("(?:public|private|protected)\\s+(?:static\\s+)?(?:\\w+(?:<[^>]+>)?)\\s+(\\w+)\\s*\\(");
        
        // JavaScript/TypeScript: function, class, const
        Pattern jsFunctionPattern = Pattern.compile("(?:function\\s+(\\w+)|(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s+)?(?:\\([^)]*\\)|[\\w\\s]*)\\s*=>|class\\s+(\\w+))");
        
        // Python: def, class
        Pattern pyDefPattern = Pattern.compile("(?:def|class)\\s+(\\w+)");
        
        // Try Java patterns
        Matcher classMatcher = javaClassPattern.matcher(content);
        while (classMatcher.find()) {
            symbols.append("- class/interface: ").append(classMatcher.group(1)).append("\\n");
        }
        
        Matcher methodMatcher = javaMethodPattern.matcher(content);
        int methodCount = 0;
        while (methodMatcher.find() && methodCount < 10) {
            symbols.append("- method: ").append(methodMatcher.group(1)).append("()\\n");
            methodCount++;
        }
        
        // Try JavaScript patterns
        if (symbols.length() == 0) {
            Matcher jsMatcher = jsFunctionPattern.matcher(content);
            while (jsMatcher.find()) {
                for (int i = 1; i <= jsMatcher.groupCount(); i++) {
                    if (jsMatcher.group(i) != null) {
                        symbols.append("- ").append(jsMatcher.group(i)).append("\\n");
                        break;
                    }
                }
            }
        }
        
        // Try Python patterns
        if (symbols.length() == 0) {
            Matcher pyMatcher = pyDefPattern.matcher(content);
            while (pyMatcher.find()) {
                symbols.append("- ").append(pyMatcher.group(1)).append("\\n");
            }
        }
        
        return symbols.length() > 0 ? symbols.toString() : null;
    }
    
    private String extractPathFromArgs(String argsJson) {
        if (argsJson == null) return null;
        try {
            JsonNode node = mapper.readTree(argsJson);
            if (node.has("path")) {
                return node.get("path").asText();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
    
    /**
     * Cache statistics
     */
    public static class CacheStats {
        public final int cachedResults;
        public final int totalReferences;
        public final int pathsTracked;
        
        public CacheStats(int cachedResults, int totalReferences, int pathsTracked) {
            this.cachedResults = cachedResults;
            this.totalReferences = totalReferences;
            this.pathsTracked = pathsTracked;
        }
        
        @Override
        public String toString() {
            return String.format("CacheStats{cached=%d, refs=%d, paths=%d}", 
                cachedResults, totalReferences, pathsTracked);
        }
    }
}
