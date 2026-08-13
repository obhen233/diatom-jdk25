package com.github.obhen233.core.tool.builtin;

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
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 轻量级项目文件搜索索引。
 * 用于 search_symbols 工具的两阶段搜索：
 * Phase 1: 文件名模糊匹配 + 内容验证
 * Phase 2: 全文内容搜索（Phase 1 无结果时降级）
 *
 * 不限制文件类型，支持 Java/JS/Vue/XML/JSP/HTML 等所有源文件。
 * 索引按需构建，任务结束后丢弃。
 */
public class CodeSearchIndex {
    private static final Logger logger = LoggerFactory.getLogger(CodeSearchIndex.class);

    private final String workspaceDir;
    private Map<String, FileEntry> fileIndex;   // path → entry
    private boolean indexBuilt;
    private long indexBuildTime;

    /** 扫描时跳过的目录 */
    private static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(
        ".git", ".svn", ".hg",
        "node_modules", "bower_components",
        "target", "build", "dist", "out",
        ".idea", ".gradle", ".mvn",
        "__pycache__", ".next", ".nuxt",
        "venv", ".venv", "env",
        "coverage", ".nyc_output",
        "logs", "tmp", "temp"
    ));

    /** 内容验证/搜索时跳过的文件扩展名（二进制格式） */
    private static final Set<String> SKIP_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".svg",
        ".woff", ".woff2", ".ttf", ".eot",
        ".zip", ".jar", ".war", ".rar", ".gz", ".7z",
        ".exe", ".dll", ".so", ".dylib",
        ".o", ".a", ".class",
        ".pdf", ".doc", ".docx", ".xls", ".xlsx",
        ".mp3", ".mp4", ".avi", ".mov"
    ));

    /** 内容搜索时单个文件最大读取字节数 */
    private static final long MAX_CONTENT_READ_BYTES = 512 * 1024; // 512KB

    /** Phase 1 内容验证时读取的前 N 字节 */
    private static final int VERIFY_HEAD_BYTES = 8192; // 8KB

    /** 内容搜索最大匹配行数/文件 */
    private static final int MAX_MATCHES_PER_FILE = 20;

    /** 内容搜索最大文件数 */
    private static final int MAX_CONTENT_SEARCH_FILES = 50;

    /** filename 匹配最大返回数 */
    public static final int MAX_FILENAME_RESULTS = 50;

    /** 查询停用词（从 classpath 加载，资源缺失时使用内置最小集合） */
    private static final Set<String> STOP_WORDS = loadStopWords();

    private static Set<String> loadStopWords() {
        Set<String> fallback = new HashSet<>(Arrays.asList(
            "的", "了", "在", "是", "和", "及", "等", "或", "与",
            "时", "时候", "选择", "没有", "有", "无", "不", "没",
            "新增", "添加", "修改", "删除", "查询", "查看", "点击", "打开",
            "怎么", "为什么", "如何", "什么", "是否"
        ));

        InputStream is = CodeSearchIndex.class.getClassLoader().getResourceAsStream("stopwords.txt");
        if (is == null) {
            logger.warn("stopwords.txt not found on classpath, using built-in fallback");
            return fallback;
        }

        Set<String> loaded = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                loaded.add(line);
            }
        } catch (IOException e) {
            logger.warn("Failed to load stopwords.txt, using built-in fallback", e);
            return fallback;
        }

        if (loaded.isEmpty()) {
            logger.warn("stopwords.txt is empty, using built-in fallback");
            return fallback;
        }
        logger.debug("Loaded {} stop words from classpath", loaded.size());
        return loaded;
    }

    public CodeSearchIndex(String workspaceDir) {
        this.workspaceDir = workspaceDir;
        this.fileIndex = new LinkedHashMap<>();
        this.indexBuilt = false;
    }

    // ========== 索引构建 ==========

    /**
     * 构建文件名索引。扫描工作区所有文件（跳过 SKIP_DIRS）。
     */
    public void buildIndex() {
        if (indexBuilt) return;
        long start = System.currentTimeMillis();
        fileIndex.clear();

        Path wsPath = Paths.get(workspaceDir);
        if (!Files.exists(wsPath)) {
            logger.warn("Workspace directory not found: {}", workspaceDir);
            indexBuilt = true;
            indexBuildTime = System.currentTimeMillis();
            return;
        }

        try {
            Files.walkFileTree(wsPath, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    // Skip common non-code directories
                    if (!dir.equals(wsPath) && isInSkipDir(dir)) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path p, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    String relPath = wsPath.relativize(p).toString().replace('\\', '/');
                    String filename = p.getFileName().toString();
                    String nameWithoutExt = filename.contains(".")
                        ? filename.substring(0, filename.lastIndexOf('.'))
                        : filename;
                    Set<String> tokens = tokenizeFilename(nameWithoutExt);

                    FileEntry entry = new FileEntry(relPath, filename, nameWithoutExt, tokens);
                    try {
                        entry.lastModified = Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                        entry.lastModified = 0;
                    }
                    fileIndex.put(relPath, entry);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Gracefully skip files/directories we can't access (e.g. $RECYCLE.BIN on Windows)
                    if (exc instanceof java.nio.file.AccessDeniedException) {
                        logger.warn("Skipping inaccessible path: {}", file);
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    logger.warn("Failed to access path: {} ({})", file, exc.getMessage());
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.warn("Failed to walk workspace: {}", e.getMessage());
        }

        indexBuilt = true;
        indexBuildTime = System.currentTimeMillis();
        long elapsed = System.currentTimeMillis() - start;
        logger.info("CodeSearchIndex built: {} files in {}ms", fileIndex.size(), elapsed);
    }

    // ========== Phase 1: 文件名匹配 ==========

    /**
     * 文件名模糊匹配。
     * 返回匹配的文件列表（按匹配度排序），每个结果包含文件名匹配信息。
     */
    public List<SearchResult> searchByFilename(String query) {
        ensureIndexBuilt();
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> queryTokens = tokenizeQuery(query);
        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }

        List<SearchResult> results = new ArrayList<>();

        for (FileEntry entry : fileIndex.values()) {
            Set<String> matchedTokens = new HashSet<>();
            int totalScore = 0;

            for (String qt : queryTokens) {
                String qtLower = qt.toLowerCase();
                for (String ft : entry.tokens) {
                    String ftLower = ft.toLowerCase();
                    // 支持子串匹配
                    if (ftLower.contains(qtLower) || qtLower.contains(ftLower)) {
                        matchedTokens.add(qt);
                        totalScore++;
                        break;
                    }
                }
                // 也检查完整文件名（不含扩展名）
                String nameLower = entry.nameWithoutExt.toLowerCase();
                if (nameLower.contains(qtLower)) {
                    matchedTokens.add(qt);
                    totalScore++;
                }
            }

            if (!matchedTokens.isEmpty()) {
                double score = (double) totalScore / (double) queryTokens.size();
                results.add(new SearchResult(entry.path, entry.filename,
                    matchedTokens, score, entry.lastModified));
            }
        }

        // 按匹配度降序排序
        results.sort((a, b) -> Double.compare(b.score, a.score));

        // 限制最大返回数，避免通用关键词（如 "audit"）返回过多结果
        if (results.size() > MAX_FILENAME_RESULTS) {
            results = results.subList(0, MAX_FILENAME_RESULTS);
        }

        return results;
    }

    /**
     * 对 Phase 1 匹配到的文件做内容验证。
     * 读取文件头部，检查 query 关键词是否出现在文件内容中。
     */
    public Map<String, ContentVerifyResult> verifyContent(List<SearchResult> filenameResults, String query) {
        Map<String, ContentVerifyResult> results = new LinkedHashMap<>();
        Set<String> queryTokens = tokenizeQuery(query);

        for (SearchResult sr : filenameResults) {
            Path fullPath = Paths.get(workspaceDir, sr.path);
            if (!Files.exists(fullPath) || Files.isDirectory(fullPath)) {
                results.put(sr.path, new ContentVerifyResult(false, Collections.emptyList()));
                continue;
            }

            String ext = getExtension(sr.filename);
            if (SKIP_EXTENSIONS.contains(ext)) {
                results.put(sr.path, new ContentVerifyResult(false, Collections.emptyList(), "binary file"));
                continue;
            }

            List<ContentMatch> matches = new ArrayList<>();
            try {
                byte[] bytes = readFileHead(fullPath, VERIFY_HEAD_BYTES);
                String content = new String(bytes, StandardCharsets.UTF_8);
                String[] lines = content.split("\n", -1);

                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    Set<String> foundTokens = new HashSet<>();
                    for (String qt : queryTokens) {
                        if (line.toLowerCase().contains(qt.toLowerCase())) {
                            foundTokens.add(qt);
                        }
                    }
                    if (!foundTokens.isEmpty()) {
                        matches.add(new ContentMatch(i + 1, line.trim(), foundTokens));
                    }
                }
            } catch (IOException e) {
                logger.debug("Failed to read content for verify: {}", sr.path);
            }

            boolean verified = !matches.isEmpty();
            results.put(sr.path, new ContentVerifyResult(verified, matches));
        }

        return results;
    }

    // ========== Phase 2: 全文内容搜索 ==========

    /**
     * 全文内容搜索。
     * 在所有文件中搜索查询关键词，返回匹配行。
     * 仅当 Phase 1 无有效结果时调用。
     */
    public Map<String, List<ContentMatch>> searchByContent(String query, List<String> includedFiles) {
        ensureIndexBuilt();
        Set<String> queryTokens = tokenizeQuery(query);
        if (queryTokens.isEmpty()) return Collections.emptyMap();

        Map<String, List<ContentMatch>> results = new LinkedHashMap<>();
        int fileCount = 0;

        Stream<String> searchPaths;
        if (includedFiles != null && !includedFiles.isEmpty()) {
            // 仅在指定文件中搜索
            searchPaths = includedFiles.stream()
                .filter(p -> fileIndex.containsKey(p) || Files.exists(Paths.get(workspaceDir, p)));
        } else {
            // 所有文件
            searchPaths = fileIndex.keySet().stream();
        }

        for (String relPath : (Iterable<String>) searchPaths::iterator) {
            if (fileCount >= MAX_CONTENT_SEARCH_FILES) break;

            Path fullPath = Paths.get(workspaceDir, relPath);
            if (!Files.exists(fullPath) || Files.isDirectory(fullPath)) continue;

            String ext = getExtension(relPath);
            if (SKIP_EXTENSIONS.contains(ext)) continue;

            List<ContentMatch> matches = searchFileContent(fullPath, queryTokens);
            if (!matches.isEmpty()) {
                results.put(relPath, matches);
                fileCount++;
            }
        }

        return results;
    }

    /**
     * 引用搜索。精确匹配符号名称，显示前后上下文用于判断引用类型。
     * 与 searchByContent 的区别：不拆分 token，将整个 query 作为完整符号匹配。
     */
    public Map<String, List<ReferenceMatch>> searchReferences(String symbol, List<String> includedFiles) {
        ensureIndexBuilt();
        if (symbol == null || symbol.trim().isEmpty()) return Collections.emptyMap();

        Map<String, List<ReferenceMatch>> results = new LinkedHashMap<>();
        int fileCount = 0;

        Stream<String> searchPaths;
        if (includedFiles != null && !includedFiles.isEmpty()) {
            searchPaths = includedFiles.stream()
                .filter(p -> fileIndex.containsKey(p) || Files.exists(Paths.get(workspaceDir, p)));
        } else if (!fileIndex.isEmpty()) {
            searchPaths = fileIndex.keySet().stream();
        } else {
            return results;
        }

        for (String relPath : (Iterable<String>) searchPaths::iterator) {
            if (fileCount >= MAX_CONTENT_SEARCH_FILES) break;

            Path fullPath = Paths.get(workspaceDir, relPath);
            if (!Files.exists(fullPath) || Files.isDirectory(fullPath)) continue;

            String ext = getExtension(relPath);
            if (SKIP_EXTENSIONS.contains(ext)) continue;

            List<ReferenceMatch> refs = searchFileReferences(fullPath, symbol);
            if (!refs.isEmpty()) {
                results.put(relPath, refs);
                fileCount++;
            }
        }

        return results;
    }

    private List<ReferenceMatch> searchFileReferences(Path fullPath, String symbol) {
        List<ReferenceMatch> matches = new ArrayList<>();
        try {
            long fileSize = Files.size(fullPath);
            if (fileSize > MAX_CONTENT_READ_BYTES) {
                return matches; // skip large files for reference search
            }

            byte[] bytes = Files.readAllBytes(fullPath);
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);

            String symbolLower = symbol.toLowerCase();

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.toLowerCase().contains(symbolLower)) {
                    // Determine reference type
                    String trimmed = line.trim();
                    String refType = guessRefType(trimmed, symbol);

                    // Collect context: 1 line before, 1 line after
                    String beforeCtx = i > 0 ? lines[i - 1].trim() : "";
                    String afterCtx = i < lines.length - 1 ? lines[i + 1].trim() : "";

                    matches.add(new ReferenceMatch(i + 1, line.trim(), beforeCtx, afterCtx, refType));
                    if (matches.size() >= MAX_MATCHES_PER_FILE) break;
                }
            }
        } catch (IOException e) {
            // skip
        }
        return matches;
    }

    /**
     * 根据行内容猜测引用类型。
     */
    private String guessRefType(String trimmedLine, String symbol) {
        String trimmed = trimmedLine.trim();

        // 检查是否在注释中
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
            return "comment";
        }

        // import 语句
        if (trimmed.startsWith("import ")) {
            return "import";
        }

        // package 声明
        if (trimmed.startsWith("package ")) {
            return "package";
        }

        // 声明：类、接口、方法、字段
        if (trimmed.matches(".*\\b(class|interface|enum|@interface)\\s+" + Pattern.quote(symbol) + "\\b.*")) {
            return "declaration";
        }

        // 字段声明
        if (trimmed.matches(".*\\b(private|protected|public)\\s+.*\\b" + Pattern.quote(symbol) + "\\s*[;=].*")) {
            return "field";
        }

        // 方法参数或局部变量声明
        if (trimmed.matches(".*\\b" + Pattern.quote(symbol) + "\\s+\\w+\\s*[=;(,].*")) {
            return "type usage";
        }

        // 方法调用
        if (trimmed.matches(".*\\b" + Pattern.quote(symbol) + "\\s*\\(.*")) {
            return "method call";
        }

        // new 实例化
        if (trimmed.matches(".*\\bnew\\s+" + Pattern.quote(symbol) + "\\s*\\(.*")) {
            return "instantiation";
        }

        // @ 注解
        if (trimmed.matches(".*@" + Pattern.quote(symbol) + "\\b.*")) {
            return "annotation";
        }

        // 默认：普通引用
        return "reference";
    }

    // ========== 内部方法 ==========

    private void ensureIndexBuilt() {
        if (!indexBuilt) {
            buildIndex();
        }
    }

    private List<ContentMatch> searchFileContent(Path fullPath, Set<String> queryTokens) {
        List<ContentMatch> matches = new ArrayList<>();
        try {
            long fileSize = Files.size(fullPath);
            if (fileSize > MAX_CONTENT_READ_BYTES) {
                // 大文件只读头部
                byte[] bytes = readFileHead(fullPath, (int) MAX_CONTENT_READ_BYTES);
                String content = new String(bytes, StandardCharsets.UTF_8);
                String[] lines = content.split("\n", -1);
                for (int i = 0; i < lines.length && matches.size() < MAX_MATCHES_PER_FILE; i++) {
                    checkLineForTokens(lines[i], i + 1, queryTokens, matches);
                }
                return matches;
            }

            // 流式读取：逐行扫描，满 MAX_MATCHES_PER_FILE 行即止，避免读完整个文件再搜索
            try (BufferedReader reader = Files.newBufferedReader(fullPath, StandardCharsets.UTF_8)) {
                String line;
                int lineNum = 0;
                while ((line = reader.readLine()) != null) {
                    lineNum++;
                    checkLineForTokens(line, lineNum, queryTokens, matches);
                    if (matches.size() >= MAX_MATCHES_PER_FILE) break;
                }
            }
        } catch (IOException e) {
            // skip unreadable files
        }
        return matches;
    }

    private void checkLineForTokens(String line, int lineNum, Set<String> queryTokens,
                                     List<ContentMatch> matches) {
        Set<String> found = new HashSet<>();
        for (String qt : queryTokens) {
            if (line.toLowerCase().contains(qt.toLowerCase())) {
                found.add(qt);
            }
        }
        if (!found.isEmpty()) {
            matches.add(new ContentMatch(lineNum, line.trim(), found));
        }
    }

    /**
     * 将查询文本分词。
     * 支持：空格分隔、驼峰拆分、下划线拆分；中文保留完整短语，不再逐字拆分。
     */
    static Set<String> tokenizeQuery(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.trim().isEmpty()) return tokens;

        String[] phrases = text.trim().split("[\\s,;，；、]+");
        for (String phrase : phrases) {
            if (phrase.isEmpty() || STOP_WORDS.contains(phrase)) continue;

            // 包含中文的短语：保留完整短语，不再逐字拆分，避免召回大量无关文件
            if (hasChineseChar(phrase)) {
                tokens.add(phrase);
            }

            tokens.addAll(tokenizeFilename(phrase));
        }

        return tokens;
    }

    /**
     * 将文件名拆分为语义 token。
     * 处理：驼峰命名、下划线命名、连字符命名
     */
    static Set<String> tokenizeFilename(String name) {
        Set<String> tokens = new LinkedHashSet<>();
        if (name == null || name.isEmpty()) return tokens;

        // 保留原始内容作为完整 token（对简短的名称有用）
        if (name.length() <= 30) {
            tokens.add(name);
        }

        StringBuilder current = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);

            if (c == '_' || c == '-' || c == '.') {
                // 分隔符：提交当前累积
                addToken(tokens, current.toString());
                current = new StringBuilder();
            } else if (Character.isUpperCase(c)) {
                // 驼峰边界
                if (current.length() > 0) {
                    addToken(tokens, current.toString());
                    current = new StringBuilder();
                }
                current.append(c);
            } else if (Character.isDigit(c)) {
                // 数字边界
                if (current.length() > 0 && !Character.isDigit(current.charAt(0))) {
                    addToken(tokens, current.toString());
                    current = new StringBuilder();
                }
                current.append(c);
            } else {
                current.append(c);
            }
        }
        addToken(tokens, current.toString());

        return tokens;
    }

    private static void addToken(Set<String> tokens, String token) {
        String t = token.trim();
        if (t.length() >= 1) {
            tokens.add(t);
        }
    }

    private static boolean hasChineseChar(String s) {
        for (char c : s.toCharArray()) {
            if (isChineseChar(c)) return true;
        }
        return false;
    }

    private static boolean isChineseChar(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
    }

    private boolean isInSkipDir(Path path) {
        for (int i = 0; i < path.getNameCount(); i++) {
            if (SKIP_DIRS.contains(path.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }

    private byte[] readFileHead(Path path, int maxBytes) throws IOException {
        if (Files.size(path) <= maxBytes) {
            return Files.readAllBytes(path);
        }
        try (java.io.InputStream is = Files.newInputStream(path)) {
            byte[] buf = new byte[maxBytes];
            int read = is.read(buf);
            if (read < maxBytes) {
                byte[] exact = new byte[read];
                System.arraycopy(buf, 0, exact, 0, read);
                return exact;
            }
            return buf;
        }
    }

    // ========== 内部类 ==========

    static class FileEntry {
        final String path;
        final String filename;
        final String nameWithoutExt;
        final Set<String> tokens;
        long lastModified;

        FileEntry(String path, String filename, String nameWithoutExt, Set<String> tokens) {
            this.path = path;
            this.filename = filename;
            this.nameWithoutExt = nameWithoutExt;
            this.tokens = tokens;
        }
    }

    public static class SearchResult {
        public final String path;
        public final String filename;
        public final Set<String> matchedTokens;
        public final double score;
        public final long lastModified;

        SearchResult(String path, String filename, Set<String> matchedTokens,
                      double score, long lastModified) {
            this.path = path;
            this.filename = filename;
            this.matchedTokens = matchedTokens;
            this.score = score;
            this.lastModified = lastModified;
        }

        public String matchedTokensString() {
            return String.join(", ", matchedTokens);
        }
    }

    public static class ContentVerifyResult {
        public final boolean verified;
        public final List<ContentMatch> matches;
        public final String note;

        public ContentVerifyResult(boolean verified, List<ContentMatch> matches) {
            this(verified, matches, null);
        }

        public ContentVerifyResult(boolean verified, List<ContentMatch> matches, String note) {
            this.verified = verified;
            this.matches = matches;
            this.note = note;
        }
    }

    public static class ContentMatch {
        public final int lineNumber;
        public final String lineContent;
        public final Set<String> matchedTokens;

        ContentMatch(int lineNumber, String lineContent, Set<String> matchedTokens) {
            this.lineNumber = lineNumber;
            this.lineContent = lineContent;
            this.matchedTokens = matchedTokens;
        }
    }

    public static class ReferenceMatch {
        public final int lineNumber;
        public final String lineContent;
        public final String beforeContext;
        public final String afterContext;
        public final String refType;

        ReferenceMatch(int lineNumber, String lineContent,
                        String beforeContext, String afterContext, String refType) {
            this.lineNumber = lineNumber;
            this.lineContent = lineContent;
            this.beforeContext = beforeContext;
            this.afterContext = afterContext;
            this.refType = refType;
        }
    }

    public int getFileCount() {
        return fileIndex.size();
    }
}
