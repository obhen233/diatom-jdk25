package com.github.obhen233.core.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.tool.annotation.ToolMethod;
import com.github.obhen233.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.github.obhen233.util.JsonUtils;

/**
 * 搜索工具。提供 search_symbols 工具，支持两阶段搜索：
 * Phase 1: 文件名模糊匹配 + 内容验证
 * Phase 2: 全文内容搜索（Phase 1 无结果时自动降级）
 *
 * BUDGET-EXEMPT: 不消耗探索预算，因为返回的是元数据而非完整文件内容。
 */
public class SearchTools {
    private static final Logger logger = LoggerFactory.getLogger(SearchTools.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();
    private static final String NEWLINE = System.lineSeparator();
    private static final int MAX_OUTPUT_CHARS = 50000;

    private final String workspaceDir;
    private CodeSearchIndex searchIndex;
    private FileSummarizer summarizer;
    private CompletableFuture<Void> indexBuildFuture;

    public SearchTools() {
        this(PathUtils.getWorkingDir());
    }

    public SearchTools(String workspaceDir) {
        this.workspaceDir = workspaceDir;
        triggerAsyncIndexBuild();
    }

    /**
     * 异步构建索引，使首次 search_symbols 调用时索引可能已就绪。
     * 如果异步构建未完成，getIndex() 会等待它完成；如果异步构建失败，则回退到同步构建。
     */
    private void triggerAsyncIndexBuild() {
        indexBuildFuture = CompletableFuture.runAsync(() -> {
            try {
                CodeSearchIndex idx = new CodeSearchIndex(workspaceDir);
                idx.buildIndex();
                synchronized (this) {
                    if (searchIndex == null) {
                        searchIndex = idx;
                    }
                }
                logger.info("Async index build complete: {} files", idx.getFileCount());
            } catch (Exception e) {
                logger.warn("Async index build failed, will build on demand", e);
            }
        });
    }


    @ToolMethod(name = "search_symbols",
                description = "[SCENE: project-explore] Search for files and symbols in the project. " +
                    "BUDGET-EXEMPT: does not count against exploration limit. " +
                    "★★★ BATCH multiple keyword variants with | separator (e.g. \"黑名单|blacklist|hmd\") ★★★ " +
                    "Two-phase search: Phase 1 = filename fuzzy match (fast), " +
                    "Phase 2 = full content search (auto-fallback when Phase 1 finds nothing). " +
                    "Supports: Java/JS/Vue/XML/JSP/HTML and any other source files. " +
                    "Use 'files' param to limit search scope to specific files. " +
                    "Use 'mode' param: 'auto' (default) = Phase1→Phase2, 'filename' = Phase1 only, 'content' = Phase2 only.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {" +
                    "\"query\": {\"type\": \"string\", \"description\": \"Search query. Batch multiple variants with | separator, e.g. '黑名单|blacklist|hmd'. Supports Chinese, English, camelCase.\"}," +
                    "\"files\": {\"type\": \"string\", \"description\": \"Optional: limit search to specific files (comma-separated relative paths)\"}," +
                    "\"mode\": {\"type\": \"string\", \"description\": \"auto|filename|content, default=auto\"}" +
                    "}, \"required\": [\"query\"]}",
                readOnly = true)
    public String searchSymbols(String argsJson) {
        try {
            JsonNode params = mapper.readTree(argsJson);
            String query = params.has("query") ? params.get("query").asText("") : "";
            String mode = params.has("mode") ? params.get("mode").asText("auto") : "auto";
            String filesStr = params.has("files") ? params.get("files").asText("") : "";

            if (query.isEmpty()) {
                return "Error: query is required";
            }

            List<String> includedFiles = parseFilesParam(filesStr);
            CodeSearchIndex index = getIndex();

            // 分割多模式查询（用 | 分隔）
            List<String> patterns = Arrays.stream(query.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

            boolean multiPattern = patterns.size() > 1;

            if (!multiPattern) {
                // 单模式：原有逻辑
                return truncateOutput(searchSinglePattern(patterns.get(0), mode, includedFiles, index));
            }

            // 多模式批量搜索
            StringBuilder sb = new StringBuilder();
            sb.append("=== search_symbols(batch: ").append(patterns.size())
              .append(" patterns) ===").append(NEWLINE);
            for (String p : patterns) {
                sb.append("  - \"").append(escape(p)).append("\"").append(NEWLINE);
            }
            sb.append(NEWLINE);

            if ("content".equals(mode)) {
                // Phase 2 only: 合并所有 pattern 为一次内容搜索
                String combinedQuery = String.join(" ", patterns);
                Map<String, List<CodeSearchIndex.ContentMatch>> contentResults =
                    index.searchByContent(combinedQuery, includedFiles);
                int totalResults = 0;
                sb.append("[Phase 2 - Content search]").append(NEWLINE).append(NEWLINE);
                for (Map.Entry<String, List<CodeSearchIndex.ContentMatch>> entry : contentResults.entrySet()) {
                    sb.append(entry.getKey()).append(NEWLINE);
                    for (CodeSearchIndex.ContentMatch m : entry.getValue()) {
                        sb.append("  line ").append(m.lineNumber).append(": ");
                        sb.append(truncateLine(m.lineContent, 120));
                        sb.append(NEWLINE);
                    }
                    sb.append(NEWLINE);
                    totalResults++;
                }
                if (totalResults == 0) {
                    sb.append("No matches found for any pattern.").append(NEWLINE);
                } else {
                    sb.append("(").append(totalResults).append(" unique files matched)").append(NEWLINE);
                }
                return truncateOutput(sb.toString());
            }

            // Phase 1: 对所有 pattern 汇总文件名结果
            Set<String> seenPaths = new LinkedHashSet<>();
            List<CodeSearchIndex.SearchResult> allFilenameResults = new ArrayList<>();
            for (String p : patterns) {
                List<CodeSearchIndex.SearchResult> results = index.searchByFilename(p);
                for (CodeSearchIndex.SearchResult sr : results) {
                    if (seenPaths.add(sr.path)) {
                        allFilenameResults.add(sr);
                    }
                }
            }

            if (!allFilenameResults.isEmpty()) {
                boolean isBroad = allFilenameResults.size() >= CodeSearchIndex.MAX_FILENAME_RESULTS;
                sb.append("[Phase 1 - Filename match: ").append(allFilenameResults.size())
                  .append(" unique results across ").append(patterns.size())
                  .append(" patterns]");
                if (isBroad) {
                    sb.append(" [WARNING] Matched ").append(allFilenameResults.size())
                      .append(" files — too broad. The list below is truncated to top ")
                      .append(CodeSearchIndex.MAX_FILENAME_RESULTS)
                      .append(". Refine keywords (more specific terms) or reduce | variants.");
                }
                sb.append(NEWLINE).append(NEWLINE);

                for (CodeSearchIndex.SearchResult sr : allFilenameResults) {
                    appendFilenameResult(sb, sr);
                    sb.append(NEWLINE);
                }

                return truncateOutput(sb.toString());
            }

            if ("filename".equals(mode)) {
                sb.append("No results found.");
                return sb.toString();
            }
            sb.append("[Fallback to Phase 2 - Full content search...]").append(NEWLINE).append(NEWLINE);

            // 合并所有 pattern 为一次内容搜索，避免 O(N×M) 次文件扫描
            String combinedQuery = String.join(" ", patterns);
            Map<String, List<CodeSearchIndex.ContentMatch>> contentResults =
                index.searchByContent(combinedQuery, includedFiles);
            int totalResults = 0;
            for (Map.Entry<String, List<CodeSearchIndex.ContentMatch>> entry : contentResults.entrySet()) {
                sb.append(entry.getKey()).append(NEWLINE);
                for (CodeSearchIndex.ContentMatch m : entry.getValue()) {
                    sb.append("  line ").append(m.lineNumber).append(": ");
                    sb.append(truncateLine(m.lineContent, 120));
                    sb.append(NEWLINE);
                }
                sb.append(NEWLINE);
                totalResults++;
            }
            if (totalResults == 0) {
                sb.append("No matches found for any pattern.").append(NEWLINE);
            } else {
                sb.append("(").append(totalResults).append(" unique files matched)").append(NEWLINE);
            }
            return truncateOutput(sb.toString());

        } catch (Exception e) {
            logger.error("search_symbols error", e);
            return "Error executing search_symbols: " + e.getMessage();
        }
    }

    /**
     * Single-pattern search (original logic, extracted for clarity)
     */
    private String searchSinglePattern(String query, String mode,
                                        List<String> includedFiles, CodeSearchIndex index) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== search_symbols(\"").append(escape(query)).append("\") ===").append(NEWLINE);

        if ("content".equals(mode)) {
            return doContentSearch(sb, index, query, includedFiles);
        }

        List<CodeSearchIndex.SearchResult> filenameResults = index.searchByFilename(query);

        if (!filenameResults.isEmpty()) {
            boolean isBroad = filenameResults.size() >= CodeSearchIndex.MAX_FILENAME_RESULTS;
            sb.append("[Phase 1 - Filename match: ").append(filenameResults.size())
              .append(" results]");
            if (isBroad) {
                sb.append(" [WARNING] Query \"").append(escape(query))
                  .append("\" matched ").append(filenameResults.size())
                  .append(" files — too broad. The list below is truncated to top ")
                  .append(CodeSearchIndex.MAX_FILENAME_RESULTS)
                  .append(". Suggest refining keywords (use more specific terms) or use fewer | variants.")
                  .append(NEWLINE)
                  .append("TIP: Phase 2 content search is SKIPPED for broad queries. Use more specific keywords.");
            }
            sb.append(NEWLINE).append(NEWLINE);

            // 对 Phase 1 结果做轻量级内容验证（只读文件头部），优先展示内容验证通过的
            Map<String, CodeSearchIndex.ContentVerifyResult> verifyResults =
                index.verifyContent(filenameResults, query);
            int verifiedCount = 0;
            for (CodeSearchIndex.SearchResult sr : filenameResults) {
                CodeSearchIndex.ContentVerifyResult vr = verifyResults.get(sr.path);
                boolean verified = vr != null && vr.verified;
                if (verified) verifiedCount++;
                appendFilenameResult(sb, sr, verified);
                sb.append(NEWLINE);
            }
            sb.append("(").append(verifiedCount).append("/").append(filenameResults.size())
              .append(" files content-verified)").append(NEWLINE);

            return sb.toString();
        }

        sb.append("[Phase 1 - No filename matches]").append(NEWLINE);
        if ("filename".equals(mode)) {
            sb.append("No results found.");
            return sb.toString();
        }
        sb.append("[Fallback to Phase 2 - Full content search...]").append(NEWLINE).append(NEWLINE);
        return doContentSearch(sb, index, query, includedFiles);
    }

    private String doContentSearch(StringBuilder sb, CodeSearchIndex index,
                                    String query, List<String> includedFiles) {
        Map<String, List<CodeSearchIndex.ContentMatch>> contentResults =
            index.searchByContent(query, includedFiles);

        if (contentResults.isEmpty()) {
            sb.append("[Phase 2 - Content search: 0 results]").append(NEWLINE);
            sb.append("No matches found for query: ").append(escape(query));
            return sb.toString();
        }

        sb.append("[Phase 2 - Content search: ").append(contentResults.size())
          .append(" results]").append(NEWLINE).append(NEWLINE);

        for (Map.Entry<String, List<CodeSearchIndex.ContentMatch>> entry : contentResults.entrySet()) {
            sb.append(entry.getKey()).append(NEWLINE);
            for (CodeSearchIndex.ContentMatch m : entry.getValue()) {
                sb.append("  line ").append(m.lineNumber).append(": ");
                sb.append(truncateLine(m.lineContent, 120));
                sb.append(NEWLINE);
            }
            sb.append(NEWLINE);
        }

        return sb.toString();
    }

    private void appendFilenameResult(StringBuilder sb, CodeSearchIndex.SearchResult sr) {
        appendFilenameResult(sb, sr, false);
    }

    private void appendFilenameResult(StringBuilder sb, CodeSearchIndex.SearchResult sr, boolean verified) {
        sb.append(sr.path);
        if (verified) {
            sb.append(" [✓ content-verified]");
        }
        sb.append(NEWLINE);
        sb.append("  └── matched: ").append(sr.matchedTokensString()).append(NEWLINE);
    }

    /**
     * Append filename search results from registered workspaces.
     */
    private void appendWorkspaceFilenameResults(StringBuilder sb,
                                                 List<CodeSearchIndex.SearchResult> wsResults,
                                                 String query) {
        if (wsResults.isEmpty()) return;
        sb.append(NEWLINE);
        sb.append("[Registered Workspaces - Filename match: ").append(wsResults.size())
          .append(" results]").append(NEWLINE).append(NEWLINE);
        for (CodeSearchIndex.SearchResult sr : wsResults) {
            appendFilenameResult(sb, sr);
            sb.append(NEWLINE);
        }
    }


    private synchronized CodeSearchIndex getIndex() {
        // 等待异步预构建（如果尚未完成）
        if (searchIndex == null && indexBuildFuture != null) {
            try {
                indexBuildFuture.get(120, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("Async index build not ready, falling back to sync build", e);
            }
        }
        if (searchIndex == null) {
            searchIndex = new CodeSearchIndex(workspaceDir);
        }
        if (searchIndex.getFileCount() == 0) {
            searchIndex.buildIndex();
        }
        return searchIndex;
    }

    private List<String> parseFilesParam(String filesStr) {
        if (filesStr == null || filesStr.trim().isEmpty()) {
            return null;
        }
        return Arrays.stream(filesStr.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    private String truncateLine(String line, int maxLen) {
        if (line == null) return "";
        if (line.length() <= maxLen) return line;
        return line.substring(0, maxLen) + "...";
    }

    /**
     * Truncate tool output at MAX_OUTPUT_CHARS to prevent context explosion.
     * Attempts to break at a newline boundary for clean truncation.
     */
    private String truncateOutput(String result) {
        if (result == null || result.length() <= MAX_OUTPUT_CHARS) {
            return result;
        }
        int cutoff = MAX_OUTPUT_CHARS;
        int lastNewline = result.lastIndexOf(NEWLINE, cutoff);
        if (lastNewline > cutoff / 2) {
            cutoff = lastNewline;
        }
        return result.substring(0, cutoff) + NEWLINE +
               "[TRUNCATED: " + result.length() + " total chars, showing first " + cutoff + "]" + NEWLINE;
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @ToolMethod(name = "summarize_file",
                description = "[SCENE: project-explore] Get a structural summary of a file without reading it fully. " +
                    "BUDGET-EXEMPT: does not count against exploration limit. " +
                    "Returns: package, imports, class/interface declarations, method signatures, fields with line numbers. " +
                    "Supports: Java, JS/TS, Vue, JSP, HTML, XML, YAML, Properties, CSS, JSON. " +
                    "Use BEFORE reading a file to understand its structure first. " +
                    "Use depth=l2 for method-level logic analysis (if/else, try/catch, loops, method calls with line numbers).",
                parametersSchema = "{\"type\": \"object\", \"properties\": {" +
                    "\"path\": {\"type\": \"string\", \"description\": \"Relative path from project root\"}," +
                    "\"depth\": {\"type\": \"string\", \"description\": \"l1 (default) or l2 (include method logic)\"}" +
                    "}, \"required\": [\"path\"]}",
                readOnly = true)
    public String summarizeFile(String argsJson) {
        try {
            JsonNode params = mapper.readTree(argsJson);
            String path = params.has("path") ? params.get("path").asText("") : "";
            String depth = params.has("depth") ? params.get("depth").asText("l1") : "l1";

            if (path.isEmpty()) {
                return "Error: path is required";
            }

            Path fullPath = Paths.get(workspaceDir, path);
            if (!Files.exists(fullPath)) {
                return "Error: File not found: " + path;
            }

            FileSummarizer fs = getSummarizer();
            return fs.summarize(fullPath, depth);

        } catch (Exception e) {
            logger.error("summarize_file error", e);
            return "Error executing summarize_file: " + e.getMessage();
        }
    }

    @ToolMethod(name = "search_references",
                description = "[SCENE: project-explore] Find all references to a symbol (class/method/field) across the project. " +
                    "BUDGET-EXEMPT: does not count against exploration limit. " +
                    "Shows file, line number, context, and reference type (import/declaration/method call/field/instantiation). " +
                    "Use 'files' param to limit scope to specific files for faster results. " +
                    "Use BEFORE modifying a symbol to understand impact.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {" +
                    "\"symbol\": {\"type\": \"string\", \"description\": \"Symbol name to search for (e.g. UserService, listSuppliers)\"}," +
                    "\"files\": {\"type\": \"string\", \"description\": \"Optional: limit search to specific files (comma-separated relative paths)\"}" +
                    "}, \"required\": [\"symbol\"]}",
                readOnly = true)
    public String searchReferences(String argsJson) {
        try {
            JsonNode params = mapper.readTree(argsJson);
            String symbol = params.has("symbol") ? params.get("symbol").asText("") : "";
            String filesStr = params.has("files") ? params.get("files").asText("") : "";

            if (symbol.isEmpty()) {
                return "Error: symbol is required";
            }

            List<String> includedFiles = parseFilesParam(filesStr);
            CodeSearchIndex index = getIndex();
            Map<String, List<CodeSearchIndex.ReferenceMatch>> refs =
                index.searchReferences(symbol, includedFiles);

            if (refs.isEmpty()) {
                return "=== search_references(\"" + escape(symbol) + "\") ===" + NEWLINE +
                       "No references found.";
            }

            int totalRefs = refs.values().stream().mapToInt(List::size).sum();
            StringBuilder sb = new StringBuilder();
            sb.append("=== References to \"").append(escape(symbol)).append("\" (")
              .append(totalRefs).append(" matches in ").append(refs.size())
              .append(" files) ===").append(NEWLINE).append(NEWLINE);

            for (Map.Entry<String, List<CodeSearchIndex.ReferenceMatch>> entry : refs.entrySet()) {
                sb.append(entry.getKey()).append(NEWLINE);
                for (CodeSearchIndex.ReferenceMatch ref : entry.getValue()) {
                    sb.append("  [").append(ref.refType).append("] :").append(ref.lineNumber).append(NEWLINE);
                    if (!ref.beforeContext.isEmpty()) {
                        sb.append("  - ").append(truncateLine(ref.beforeContext, 80)).append(NEWLINE);
                    }
                    sb.append("  > ").append(truncateLine(ref.lineContent, 80)).append(NEWLINE);
                    if (!ref.afterContext.isEmpty()) {
                        sb.append("  + ").append(truncateLine(ref.afterContext, 80)).append(NEWLINE);
                    }
                }
                sb.append(NEWLINE);
            }

            return truncateOutput(sb.toString());

        } catch (Exception e) {
            logger.error("search_references error", e);
            return "Error executing search_references: " + e.getMessage();
        }
    }

    private synchronized FileSummarizer getSummarizer() {
        if (summarizer == null) {
            summarizer = new FileSummarizer();
        }
        return summarizer;
    }
}
