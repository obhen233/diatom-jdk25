package com.github.obhen233.compiler.service;

import com.github.obhen233.compiler.constant.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 项目文件和符号索引服务。
 * 为每个项目维护文件名索引和 Java 符号索引（类名、方法名、字段名），
 * 支持模糊搜索和增量更新。
 */
@Service
public class ProjectIndexService {

    private static final Logger log = LoggerFactory.getLogger(ProjectIndexService.class);

    /** 项目索引缓存: projectName -> ProjectIndex */
    private final Map<String, ProjectIndex> indexCache = new ConcurrentHashMap<>();

    /** 索引构建时间戳: projectName -> timestamp */
    private final Map<String, Long> indexTimestamps = new ConcurrentHashMap<>();

    /** 索引有效期 (ms)，超过后下次搜索时自动重建 */
    private static final long INDEX_TTL = 60_000; // 60 seconds

    // ==================== 公开 API ====================

    /**
     * 搜索项目中的文件和符号。
     *
     * @param projectName 项目名
     * @param query       搜索关键词（支持模糊匹配）
     * @param type        搜索类型: "all", "file", "symbol"
     * @param maxResults  最大返回数
     * @return 搜索结果列表
     */
    public List<SearchResult> search(String projectName, String query, String type, int maxResults, String ext) {
        if (query == null || query.trim().isEmpty()) return Collections.emptyList();
        query = query.trim();

        ProjectIndex index = getOrBuildIndex(projectName);
        if (index == null) return Collections.emptyList();

        List<SearchResult> results = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        // 解析扩展名过滤: "*.java", ".java", "java", "*.*" (全部), 逗号分隔多个
        Set<String> extFilter = parseExtFilter(ext);

        if ("all".equals(type) || "file".equals(type)) {
            searchFiles(index, lowerQuery, results, maxResults, extFilter);
        }
        if ("all".equals(type) || "symbol".equals(type)) {
            searchSymbols(index, lowerQuery, query, results, maxResults, extFilter);
        }

        // 按相关度排序: 精确匹配 > 前缀匹配 > 包含匹配 > 模糊匹配
        results.sort((a, b) -> {
            int cmp = Integer.compare(a.matchScore, b.matchScore);
            if (cmp != 0) return cmp;
            return a.name.compareToIgnoreCase(b.name);
        });

        if (results.size() > maxResults) {
            return results.subList(0, maxResults);
        }
        return results;
    }

    /**
     * 强制重建指定项目的索引。
     */
    public void rebuildIndex(String projectName) {
        indexCache.remove(projectName);
        indexTimestamps.remove(projectName);
        getOrBuildIndex(projectName);
    }

    /**
     * 通知索引某个文件已变更（增量更新）。
     */
    public void notifyFileChanged(String projectName, String filePath, String content) {
        ProjectIndex index = indexCache.get(projectName);
        if (index == null) return;
        index.updateFile(filePath, content);
    }

    /**
     * 通知索引某个文件已删除。
     */
    public void notifyFileDeleted(String projectName, String filePath) {
        ProjectIndex index = indexCache.get(projectName);
        if (index == null) return;
        index.removeFile(filePath);
    }

    // ==================== 内部实现 ====================

    private ProjectIndex getOrBuildIndex(String projectName) {
        Long ts = indexTimestamps.get(projectName);
        if (ts != null && (System.currentTimeMillis() - ts) < INDEX_TTL) {
            ProjectIndex cached = indexCache.get(projectName);
            if (cached != null) return cached;
        }
        return buildIndex(projectName);
    }

    private ProjectIndex buildIndex(String projectName) {
        String wsPath = Constants.workspacePath;
        if (wsPath == null) return null;
        File projectDir = new File(wsPath, projectName);
        if (!projectDir.exists() || !projectDir.isDirectory()) return null;

        long start = System.currentTimeMillis();
        ProjectIndex index = new ProjectIndex(projectName);

        try {
            Files.walkFileTree(projectDir.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String absPath = file.toString();
                    String relPath = projectDir.toPath().relativize(file).toString().replace('\\', '/');

                    // 跳过隐藏文件和编译输出
                    if (relPath.startsWith(".") || relPath.contains("/.") ||
                        relPath.startsWith("target/") || relPath.startsWith("build/") ||
                        relPath.startsWith("node_modules/")) {
                        return FileVisitResult.CONTINUE;
                    }

                    String fileName = file.getFileName().toString();
                    index.addFile(relPath, fileName);

                    // 对 Java 文件提取符号
                    if (fileName.endsWith(".java")) {
                        try {
                            String content = new String(Files.readAllBytes(file), "UTF-8");
                            List<SymbolEntry> symbols = extractSymbols(content, relPath);
                            index.addSymbols(relPath, symbols);
                        } catch (Exception e) {
                            // 忽略读取失败的文件
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.startsWith(".") || "target".equals(name) || "build".equals(name) || "node_modules".equals(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("[Index] Failed to build index for {}: {}", projectName, e.getMessage());
        }

        indexCache.put(projectName, index);
        indexTimestamps.put(projectName, System.currentTimeMillis());
        log.info("[Index] Built index for {}: {} files, {} symbols in {}ms", projectName, index.fileCount(), index.symbolCount(), System.currentTimeMillis() - start);
        return index;
    }

    // ==================== 符号提取 ====================

    /** 匹配类/接口/枚举声明 */
    private static final Pattern CLASS_PATTERN = Pattern.compile(
        "(?:public|protected|private)?\\s*(?:abstract\\s+|static\\s+|final\\s+)*" +
        "(class|interface|enum|@interface)\\s+(\\w+)");

    /** 匹配方法声明 */
    private static final Pattern METHOD_PATTERN = Pattern.compile(
        "(?:public|protected|private)?\\s*(?:static\\s+|final\\s+|abstract\\s+|synchronized\\s+|native\\s+)*" +
        "(?:<[^>]+>\\s+)?([\\w<>\\[\\],\\s]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)");

    /** 匹配字段声明 */
    private static final Pattern FIELD_PATTERN = Pattern.compile(
        "(?:public|protected|private)\\s+(?:static\\s+|final\\s+|volatile\\s+|transient\\s+)*" +
        "([\\w<>\\[\\],\\s]+?)\\s+(\\w+)\\s*[=;]");

    /** 匹配 package 声明 */
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
        "^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /**
     * 从 Java 源码中提取符号（类名、方法名、字段名）。
     */
    static List<SymbolEntry> extractSymbols(String source, String filePath) {
        List<SymbolEntry> symbols = new ArrayList<>();

        // 提取包名
        String packageName = "";
        Matcher pkgMatcher = PACKAGE_PATTERN.matcher(source);
        if (pkgMatcher.find()) packageName = pkgMatcher.group(1);

        // 去除注释和字符串，避免误匹配
        String cleaned = removeCommentsAndStrings(source);
        String[] lines = source.split("\n");

        // 提取类/接口/枚举
        String currentClass = "";
        Matcher classMatcher = CLASS_PATTERN.matcher(cleaned);
        while (classMatcher.find()) {
            String kind = classMatcher.group(1);
            String name = classMatcher.group(2);
            currentClass = name;
            int line = countLines(source, classMatcher.start());
            String fullName = packageName.isEmpty() ? name : packageName + "." + name;
            String symbolKind;
            switch (kind) {
                case "interface": symbolKind = "interface"; break;
                case "enum": symbolKind = "enum"; break;
                case "@interface": symbolKind = "annotation"; break;
                default: symbolKind = "class";
            }
            symbols.add(new SymbolEntry(name, symbolKind, fullName, filePath, line));
        }

        // 提取方法
        Matcher methodMatcher = METHOD_PATTERN.matcher(cleaned);
        while (methodMatcher.find()) {
            String returnType = methodMatcher.group(1).trim();
            String name = methodMatcher.group(2);
            String params = methodMatcher.group(3).trim();
            // 跳过构造器（返回类型 = 类名）和控制流关键字
            if (isKeyword(name) || isKeyword(returnType)) continue;
            int line = countLines(source, methodMatcher.start());
            String detail = returnType + " " + name + "(" + abbreviateParams(params) + ")";
            String container = currentClass.isEmpty() ? "" : currentClass + ".";
            symbols.add(new SymbolEntry(name, "method", container + name, filePath, line, detail));
        }

        // 提取字段（仅 public/protected/private 修饰的）
        Matcher fieldMatcher = FIELD_PATTERN.matcher(cleaned);
        while (fieldMatcher.find()) {
            String type = fieldMatcher.group(1).trim();
            String name = fieldMatcher.group(2);
            if (isKeyword(name) || isKeyword(type)) continue;
            int line = countLines(source, fieldMatcher.start());
            String detail = type + " " + name;
            symbols.add(new SymbolEntry(name, "field", currentClass + "." + name, filePath, line, detail));
        }

        return symbols;
    }

    private static String removeCommentsAndStrings(String source) {
        StringBuilder sb = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (i + 1 < source.length() && source.charAt(i) == '/' && source.charAt(i + 1) == '/') {
                // 行注释
                while (i < source.length() && source.charAt(i) != '\n') { sb.append(' '); i++; }
            } else if (i + 1 < source.length() && source.charAt(i) == '/' && source.charAt(i + 1) == '*') {
                // 块注释
                sb.append(' '); i += 2;
                while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    sb.append(source.charAt(i) == '\n' ? '\n' : ' '); i++;
                }
                if (i + 1 < source.length()) { sb.append("  "); i += 2; }
            } else if (source.charAt(i) == '"') {
                // 字符串
                sb.append(' '); i++;
                while (i < source.length() && source.charAt(i) != '"') {
                    if (source.charAt(i) == '\\') { sb.append(' '); i++; }
                    sb.append(' '); i++;
                }
                if (i < source.length()) { sb.append(' '); i++; }
            } else if (source.charAt(i) == '\'') {
                // 字符
                sb.append(' '); i++;
                while (i < source.length() && source.charAt(i) != '\'') {
                    if (source.charAt(i) == '\\') { sb.append(' '); i++; }
                    sb.append(' '); i++;
                }
                if (i < source.length()) { sb.append(' '); i++; }
            } else {
                sb.append(source.charAt(i)); i++;
            }
        }
        return sb.toString();
    }

    private static int countLines(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static String abbreviateParams(String params) {
        if (params.isEmpty()) return "";
        String[] parts = params.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", ");
            String p = parts[i].trim();
            String[] tokens = p.split("\\s+");
            sb.append(tokens.length > 0 ? tokens[tokens.length > 1 ? tokens.length - 2 : 0] : p);
        }
        return sb.toString();
    }

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
        "return", "try", "catch", "finally", "throw", "throws", "new", "class",
        "interface", "enum", "extends", "implements", "import", "package",
        "public", "private", "protected", "static", "final", "abstract",
        "void", "int", "long", "double", "float", "boolean", "char", "byte", "short",
        "this", "super", "null", "true", "false", "instanceof", "synchronized",
        "volatile", "transient", "native", "strictfp", "assert", "default"
    ));

    private static boolean isKeyword(String word) {
        return KEYWORDS.contains(word);
    }

    // ==================== 搜索逻辑 ====================

    private void searchFiles(ProjectIndex index, String lowerQuery, List<SearchResult> results, int max, Set<String> extFilter) {
        for (FileEntry file : index.files.values()) {
            if (results.size() >= max) break;
            if (!matchesExtFilter(file.fileName, extFilter)) continue;
            int score = fuzzyMatch(file.fileName.toLowerCase(), lowerQuery);
            if (score >= 0) {
                results.add(new SearchResult("file", file.fileName, file.relPath, "", 0, score, getFileIcon(file.fileName)));
            }
        }
    }

    private void searchSymbols(ProjectIndex index, String lowerQuery, String originalQuery,
                               List<SearchResult> results, int max, Set<String> extFilter) {
        for (Map.Entry<String, List<SymbolEntry>> entry : index.symbols.entrySet()) {
            String filePath = entry.getKey();
            // 如果有扩展名过滤，检查符号所在文件是否匹配
            if (!extFilter.isEmpty()) {
                String fileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
                if (!matchesExtFilter(fileName, extFilter)) continue;
            }
            for (SymbolEntry sym : entry.getValue()) {
                if (results.size() >= max) break;
                int score = fuzzyMatch(sym.name.toLowerCase(), lowerQuery);
                if (score < 0) {
                    score = camelCaseMatch(sym.name, originalQuery);
                }
                if (score >= 0) {
                    results.add(new SearchResult("symbol", sym.name, sym.filePath,
                            sym.detail != null ? sym.detail : sym.fullName,
                            sym.line, score, getSymbolIcon(sym.kind)));
                }
            }
        }
    }

    /**
     * 解析扩展名过滤字符串。
     * 支持: "*.java", ".java", "java", "*.*"(全部), 逗号分隔多个
     * 返回小写扩展名集合(不含点)，空集合表示不过滤
     */
    private Set<String> parseExtFilter(String ext) {
        Set<String> result = new HashSet<>();
        if (ext == null || ext.trim().isEmpty() || "*.*".equals(ext.trim())) return result;
        for (String part : ext.split("[,;\\s]+")) {
            String s = part.trim().toLowerCase();
            if (s.isEmpty() || "*.*".equals(s)) { result.clear(); return result; }
            // 去掉 *. 或 . 前缀
            if (s.startsWith("*.")) s = s.substring(2);
            else if (s.startsWith(".")) s = s.substring(1);
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    /** 检查文件名是否匹配扩展名过滤 */
    private boolean matchesExtFilter(String fileName, Set<String> extFilter) {
        if (extFilter.isEmpty()) return true;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = fileName.substring(dot + 1).toLowerCase();
        return extFilter.contains(ext);
    }

    /**
     * 模糊匹配算法，返回匹配分数（越小越好），-1 表示不匹配。
     * 精确匹配=0, 前缀匹配=1, 包含匹配=2, 子序列匹配=3
     */
    static int fuzzyMatch(String text, String query) {
        if (text.equals(query)) return 0;
        if (text.startsWith(query)) return 1;
        if (text.contains(query)) return 2;
        // 子序列匹配
        int qi = 0;
        for (int ti = 0; ti < text.length() && qi < query.length(); ti++) {
            if (text.charAt(ti) == query.charAt(qi)) qi++;
        }
        return qi == query.length() ? 3 : -1;
    }

    /**
     * 驼峰匹配：输入大写字母序列匹配驼峰命名。
     * 例如 "gMC" 匹配 "guessMainClass"
     */
    static int camelCaseMatch(String symbolName, String query) {
        if (query.isEmpty()) return -1;
        // 提取符号名中的大写字母起始的片段
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 1; i < symbolName.length(); i++) {
            if (Character.isUpperCase(symbolName.charAt(i))) {
                parts.add(symbolName.substring(start, i).toLowerCase());
                start = i;
            }
        }
        parts.add(symbolName.substring(start).toLowerCase());

        String lowerQuery = query.toLowerCase();
        int qi = 0;
        for (String part : parts) {
            if (qi >= lowerQuery.length()) break;
            if (part.startsWith(String.valueOf(lowerQuery.charAt(qi)))) {
                // 尝试匹配尽可能多的字符
                int matchLen = 0;
                while (matchLen < part.length() && qi + matchLen < lowerQuery.length()
                       && part.charAt(matchLen) == lowerQuery.charAt(qi + matchLen)) {
                    matchLen++;
                }
                qi += matchLen;
            }
        }
        return qi == lowerQuery.length() ? 4 : -1;
    }

    private String getFileIcon(String fileName) {
        if (fileName.endsWith(".java")) return "☕";
        if (fileName.endsWith(".xml")) return "📋";
        if (fileName.endsWith(".jar")) return "🫙";
        if (fileName.endsWith(".properties")) return "⚙";
        if (fileName.endsWith(".gradle")) return "🐘";
        if (fileName.endsWith(".json")) return "📄";
        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) return "📄";
        return "📄";
    }

    private String getSymbolIcon(String kind) {
        switch (kind) {
            case "class": return "🔷";
            case "interface": return "🔶";
            case "enum": return "📊";
            case "annotation": return "🏷";
            case "method": return "🔧";
            case "field": return "📌";
            default: return "•";
        }
    }

    // ==================== 代码导航 ====================

    /**
     * 根据光标位置获取当前单词（标识符）
     */
    private static String getWordAt(String source, int line, int column) {
        String[] lines = source.split("\n", -1);
        if (line < 1 || line > lines.length) return null;
        String lineText = lines[line - 1];
        if (column < 1 || column > lineText.length() + 1) return null;
        int pos = column - 1;
        // 向左找单词起始
        int start = pos;
        while (start > 0 && Character.isJavaIdentifierPart(lineText.charAt(start - 1))) start--;
        // 向右找单词结束
        int end = pos;
        while (end < lineText.length() && Character.isJavaIdentifierPart(lineText.charAt(end))) end++;
        if (start >= end) return null;
        return lineText.substring(start, end);
    }

    /**
     * Go to Definition: 查找符号定义位置（类/方法/字段）
     */
    public List<NavigationResult> findDefinition(String projectName, String filePath, int line, int column) {
        ProjectIndex index = getOrBuildIndex(projectName);
        if (index == null) return Collections.emptyList();

        // 读取当前文件内容获取光标处的单词
        String source = readProjectFile(projectName, filePath);
        if (source == null) return Collections.emptyList();
        String word = getWordAt(source, line, column);
        if (word == null || word.isEmpty() || isKeyword(word)) return Collections.emptyList();

        List<NavigationResult> results = new ArrayList<>();

        // 在所有符号中查找匹配的定义
        for (Map.Entry<String, List<SymbolEntry>> entry : index.symbols.entrySet()) {
            for (SymbolEntry sym : entry.getValue()) {
                if (sym.name.equals(word)) {
                    // 优先匹配类/接口/枚举定义
                    if ("class".equals(sym.kind) || "interface".equals(sym.kind)
                            || "enum".equals(sym.kind) || "annotation".equals(sym.kind)) {
                        results.add(0, new NavigationResult(sym.filePath, sym.line, 1, sym.name, sym.kind, sym.fullName));
                    } else {
                        results.add(new NavigationResult(sym.filePath, sym.line, 1, sym.name, sym.kind,
                                sym.detail != null ? sym.detail : sym.fullName));
                    }
                }
            }
        }

        // 如果没找到，尝试按全限定名的简单类名匹配
        if (results.isEmpty()) {
            for (Map.Entry<String, List<SymbolEntry>> entry : index.symbols.entrySet()) {
                for (SymbolEntry sym : entry.getValue()) {
                    if (("class".equals(sym.kind) || "interface".equals(sym.kind) || "enum".equals(sym.kind))
                            && sym.fullName.endsWith("." + word)) {
                        results.add(new NavigationResult(sym.filePath, sym.line, 1, sym.name, sym.kind, sym.fullName));
                    }
                }
            }
        }

        return results;
    }

    /**
     * Go to Implementation: 查找接口的实现类
     */
    public List<NavigationResult> findImplementations(String projectName, String filePath, int line, int column) {
        ProjectIndex index = getOrBuildIndex(projectName);
        if (index == null) {
            log.debug("[findImplementations] index is null for project: {}", projectName);
            return Collections.emptyList();
        }

        String source = readProjectFile(projectName, filePath);
        if (source == null) {
            log.debug("[findImplementations] source is null for file: {}", filePath);
            return Collections.emptyList();
        }
        String word = getWordAt(source, line, column);
        if (word == null || word.isEmpty()) {
            log.debug("[findImplementations] word is null/empty at line:{}, column:{}", line, column);
            return Collections.emptyList();
        }
        log.debug("[findImplementations] word='{}', file='{}', line={}, column={}", word, filePath, line, column);

        // 先确认 word 是一个接口或抽象类
        boolean isInterface = false;
        boolean isAbstractClass = false;
        for (Map.Entry<String, List<SymbolEntry>> entry : index.symbols.entrySet()) {
            for (SymbolEntry sym : entry.getValue()) {
                if (sym.name.equals(word)) {
                    log.debug("[findImplementations] found symbol: name='{}', kind='{}', file='{}'", sym.name, sym.kind, sym.filePath);
                    if ("interface".equals(sym.kind)) isInterface = true;
                    if ("class".equals(sym.kind)) isAbstractClass = true;
                }
            }
        }
        log.debug("[findImplementations] isInterface={}, isAbstractClass={}", isInterface, isAbstractClass);

        List<NavigationResult> results = new ArrayList<>();

        // 扫描所有 Java 文件，查找 implements/extends 该接口/类的类
        for (Map.Entry<String, FileEntry> fileEntry : index.files.entrySet()) {
            String fp = fileEntry.getKey();
            if (!fp.endsWith(".java")) continue;
            String fileSource = readProjectFile(projectName, fp);
            if (fileSource == null) continue;
            String cleaned = removeCommentsAndStrings(fileSource);

            // 查找 implements Word 或 extends Word
            Pattern implPattern = Pattern.compile(
                    "(?:class|interface)\\s+(\\w+)\\s+(?:[^{]*\\s)?(?:implements|extends)\\s+[^{]*\\b" + Pattern.quote(word) + "\\b",
                    Pattern.DOTALL);
            Matcher m = implPattern.matcher(cleaned);
            while (m.find()) {
                String implClassName = m.group(1);
                if (implClassName.equals(word)) continue; // 跳过自身
                // 找到实现类在符号表中的位置
                for (SymbolEntry sym : index.symbols.getOrDefault(fp, Collections.emptyList())) {
                    if (sym.name.equals(implClassName) && ("class".equals(sym.kind) || "interface".equals(sym.kind))) {
                        results.add(new NavigationResult(sym.filePath, sym.line, 1, sym.name, sym.kind, sym.fullName));
                    }
                }
            }
        }

        // 如果 word 是方法名，查找接口方法的实现
        if (results.isEmpty()) {
            // 找到当前文件中 word 所在的类
            String currentClass = findEnclosingClass(source, line);
            if (currentClass != null) {
                // 查找该类的所有实现类中同名方法
                List<NavigationResult> implClasses = new ArrayList<>();
                for (Map.Entry<String, FileEntry> fe : index.files.entrySet()) {
                    if (!fe.getKey().endsWith(".java")) continue;
                    String fs = readProjectFile(projectName, fe.getKey());
                    if (fs == null) continue;
                    String cl = removeCommentsAndStrings(fs);
                    Pattern p = Pattern.compile(
                            "class\\s+(\\w+)\\s+(?:[^{]*\\s)?(?:implements|extends)\\s+[^{]*\\b" + Pattern.quote(currentClass) + "\\b",
                            Pattern.DOTALL);
                    Matcher mm = p.matcher(cl);
                    while (mm.find()) {
                        String implName = mm.group(1);
                        // 在实现类中查找同名方法
                        for (SymbolEntry sym : index.symbols.getOrDefault(fe.getKey(), Collections.emptyList())) {
                            if (sym.name.equals(word) && "method".equals(sym.kind)) {
                                results.add(new NavigationResult(sym.filePath, sym.line, 1, sym.name, sym.kind,
                                        sym.detail != null ? sym.detail : sym.fullName));
                            }
                        }
                    }
                }
            }
        }

        log.debug("[findImplementations] returning {} results for word='{}'", results.size(), word);
        return results;
    }

    /**
     * Find All References: 查找符号在项目中的所有引用
     */
    public List<NavigationResult> findReferences(String projectName, String filePath, int line, int column) {
        ProjectIndex index = getOrBuildIndex(projectName);
        if (index == null) return Collections.emptyList();

        String source = readProjectFile(projectName, filePath);
        if (source == null) return Collections.emptyList();
        String word = getWordAt(source, line, column);
        if (word == null || word.isEmpty() || isKeyword(word)) return Collections.emptyList();

        List<NavigationResult> results = new ArrayList<>();
        Pattern refPattern = Pattern.compile("\\b" + Pattern.quote(word) + "\\b");

        // 扫描所有 Java 文件
        for (Map.Entry<String, FileEntry> fileEntry : index.files.entrySet()) {
            String fp = fileEntry.getKey();
            if (!fp.endsWith(".java")) continue;
            String fileSource = readProjectFile(projectName, fp);
            if (fileSource == null) continue;
            String cleaned = removeCommentsAndStrings(fileSource);
            String[] fileLines = fileSource.split("\n", -1);
            String[] cleanedLines = cleaned.split("\n", -1);

            for (int i = 0; i < cleanedLines.length; i++) {
                Matcher m = refPattern.matcher(cleanedLines[i]);
                while (m.find()) {
                    int col = m.start() + 1;
                    // 获取原始行文本作为上下文
                    String context = i < fileLines.length ? fileLines[i].trim() : "";
                    if (context.length() > 120) context = context.substring(0, 120) + "...";
                    results.add(new NavigationResult(fp, i + 1, col, word, "reference", context));
                }
            }
        }

        return results;
    }

    /**
     * 找到指定行所在的类名
     */
    private String findEnclosingClass(String source, int targetLine) {
        String cleaned = removeCommentsAndStrings(source);
        Matcher m = CLASS_PATTERN.matcher(cleaned);
        String lastClass = null;
        while (m.find()) {
            int classLine = countLines(source, m.start());
            if (classLine <= targetLine) {
                lastClass = m.group(2);
            } else {
                break;
            }
        }
        return lastClass;
    }

    /**
     * 读取项目文件内容
     */
    private String readProjectFile(String projectName, String relPath) {
        String wsPath = Constants.workspacePath;
        if (wsPath == null) return null;
        File file = new File(wsPath + File.separator + projectName, relPath);
        if (!file.exists() || !file.isFile()) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 数据结构 ====================

    /** 项目索引 */
    static class ProjectIndex {
        final String projectName;
        final Map<String, FileEntry> files = new LinkedHashMap<>();
        final Map<String, List<SymbolEntry>> symbols = new LinkedHashMap<>();

        ProjectIndex(String projectName) { this.projectName = projectName; }

        void addFile(String relPath, String fileName) {
            files.put(relPath, new FileEntry(relPath, fileName));
        }

        void addSymbols(String filePath, List<SymbolEntry> syms) {
            if (!syms.isEmpty()) symbols.put(filePath, syms);
        }

        /** 增量更新：重新索引单个文件 */
        void updateFile(String relPath, String content) {
            String fileName = relPath.contains("/") ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
            files.put(relPath, new FileEntry(relPath, fileName));
            if (fileName.endsWith(".java") && content != null) {
                List<SymbolEntry> syms = extractSymbols(content, relPath);
                if (!syms.isEmpty()) symbols.put(relPath, syms);
                else symbols.remove(relPath);
            }
        }

        void removeFile(String relPath) {
            files.remove(relPath);
            symbols.remove(relPath);
        }

        int fileCount() { return files.size(); }
        int symbolCount() { return symbols.values().stream().mapToInt(List::size).sum(); }
    }

    static class FileEntry {
        final String relPath;
        final String fileName;
        FileEntry(String relPath, String fileName) {
            this.relPath = relPath;
            this.fileName = fileName;
        }
    }

    static class SymbolEntry {
        final String name;
        final String kind;       // class, interface, enum, method, field
        final String fullName;   // 全限定名或 ClassName.methodName
        final String filePath;
        final int line;
        final String detail;     // 方法签名等

        SymbolEntry(String name, String kind, String fullName, String filePath, int line) {
            this(name, kind, fullName, filePath, line, null);
        }

        SymbolEntry(String name, String kind, String fullName, String filePath, int line, String detail) {
            this.name = name;
            this.kind = kind;
            this.fullName = fullName;
            this.filePath = filePath;
            this.line = line;
            this.detail = detail;
        }
    }

    /** 搜索结果 */
    public static class SearchResult {
        public String type;      // "file" or "symbol"
        public String name;
        public String path;
        public String detail;
        public int line;
        public int matchScore;
        public String icon;

        public SearchResult(String type, String name, String path, String detail,
                            int line, int matchScore, String icon) {
            this.type = type;
            this.name = name;
            this.path = path;
            this.detail = detail;
            this.line = line;
            this.matchScore = matchScore;
            this.icon = icon;
        }
    }

    /** 导航结果（定义跳转/实现查找/引用查找） */
    public static class NavigationResult {
        public String filePath;
        public int line;
        public int column;
        public String name;
        public String kind;      // class, interface, method, field, reference
        public String detail;

        public NavigationResult(String filePath, int line, int column, String name, String kind, String detail) {
            this.filePath = filePath;
            this.line = line;
            this.column = column;
            this.name = name;
            this.kind = kind;
            this.detail = detail;
        }
    }
}
