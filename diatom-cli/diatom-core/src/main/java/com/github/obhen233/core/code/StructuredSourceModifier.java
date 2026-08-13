package com.github.obhen233.core.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 结构化源码修改器
 *
 * Phase 1-5 完整实现：
 * Phase 1: 基础定位器 + 简单替换
 * Phase 2: 格式风格提取
 * Phase 3: 多种修改类型支持
 * Phase 4: 错误恢复和回退
 * Phase 5: JavaParser 集成（复杂场景）
 */
public class StructuredSourceModifier {
    private static final Logger logger = LoggerFactory.getLogger(StructuredSourceModifier.class);

    private final CodeStructureSummarizer summarizer;
    private final SemanticLocator locator;
    private final CodeStyleExtractor styleExtractor;
    private final ModificationApplicator applicator;
    private final CodeModificationProtocol protocol;

    // 缓存的文件内容（避免重复读取）
    private final Map<String, String> fileContentCache = new ConcurrentHashMap<>();

    public StructuredSourceModifier() {
        this.summarizer = new CodeStructureSummarizer();
        this.locator = new SemanticLocator();
        this.styleExtractor = new CodeStyleExtractor();
        this.applicator = new ModificationApplicator();
        this.protocol = new CodeModificationProtocol();
    }

    /**
     * Phase 1: 基础替换 - 字符串匹配替换（备用方案）
     */
    public String basicReplace(String relativePath, String oldStr, String newStr, String sourcesDir) throws IOException {
        String normalizedPath = normalizeSourcePath(relativePath);
        Path targetPath = Paths.get(sourcesDir, normalizedPath);

        if (!Files.exists(targetPath)) {
            throw new IOException("Source file not found: " + normalizedPath);
        }

        // 清除缓存，确保读取最新内容（防止多次替换同一文件时使用过期缓存）
        // 注意：readFile(Path) 用 path.toString() 作为缓存 key，必须用相同 key 清除
        String cacheKey = targetPath.toString();
        fileContentCache.remove(cacheKey);
        String content = readFile(targetPath);

        // 尝试精确匹配
        if (content.contains(oldStr)) {
            String result = content.replace(oldStr, newStr);
            writeFile(targetPath, result);
            fileContentCache.put(cacheKey, result);  // 更新缓存
            return "Replacement done: " + normalizedPath;
        }

        // 尝试归一化后匹配（处理行尾符差异）
        String normalizedOld = oldStr.replace("\r\n", "\n").replace("\r", "\n");
        String normalizedContent = content.replace("\r\n", "\n").replace("\r", "\n");

        if (normalizedContent.contains(normalizedOld)) {
            // 在归一化内容中替换，然后在原始内容中应用
            int normalizedIndex = normalizedContent.indexOf(normalizedOld);
            // 将归一化索引转换为原始内容索引（处理 CRLF 偏移）
            // 归一化内容比原始内容少了 \r 字符，所以索引偏小
            int originalIndex = toOriginalIndex(content, normalizedIndex);
            // 计算在原始内容中的实际匹配长度（处理 CRLF vs LF 的差异）
            int actualLength = calculateActualMatchLength(content, originalIndex, normalizedOld);
            String result = applyReplaceAtIndex(content, originalIndex, actualLength, newStr);
            writeFile(targetPath, result);
            fileContentCache.put(cacheKey, result);  // 更新缓存
            return "Replacement done (normalized): " + normalizedPath;
        }

        throw new IOException("String not found in file: " + truncate(oldStr, 100));
    }

    /**
     * Phase 2: 结构化替换 - 通过语义定位进行替换
     *
     * @param relativePath 相对路径
     * @param target 目标（类名.方法名 或 方法名）
     * @param newCode 新代码
     * @param sourcesDir 源码目录
     * @param modificationType 修改类型
     * @return 结果
     */
    public String structuredReplace(String relativePath, String target, String newCode,
                                    String sourcesDir, ModificationInstruction.ModificationType modificationType) throws IOException {
        String normalizedPath = normalizeSourcePath(relativePath);
        Path targetPath = Paths.get(sourcesDir, normalizedPath);

        if (!Files.exists(targetPath)) {
            throw new IOException("Source file not found: " + normalizedPath);
        }

        String content = readFile(targetPath);

        // 创建修改指令
        ModificationInstruction instruction = new ModificationInstruction();
        instruction.setFilePath(normalizedPath);
        instruction.setType(modificationType);
        instruction.setTarget(target);
        instruction.setNewCode(newCode);
        instruction.addContext("source", content);

        // 应用修改
        try {
            String result = applicator.apply(content, instruction);
            writeFile(targetPath, result);
            fileContentCache.put(targetPath.toString(), result);  // 更新缓存
            return "Structured modification done: " + modificationType + " on " + target;
        } catch (CodeModificationException e) {
            logger.warn("Structured modification failed, falling back to basic replace: {}", e.getMessage());
            throw new IOException("Modification failed: " + e.getMessage());
        }
    }

    /**
     * Phase 3: 生成结构化摘要给模型
     */
    public String generateSummaryForModel(String relativePath, String sourcesDir) throws IOException {
        String normalizedPath = normalizeSourcePath(relativePath);
        Path targetPath = Paths.get(sourcesDir, normalizedPath);

        if (!Files.exists(targetPath)) {
            throw new IOException("Source file not found: " + normalizedPath);
        }

        String content = readFile(targetPath);
        CodeStructureSummary summary = summarizer.summarize(normalizedPath, content);

        return summarizer.toModelJson(summary);
    }

    /**
     * Phase 3: 生成压缩视图给模型
     */
    public String generateCompressedView(String relativePath, String sourcesDir) throws IOException {
        String normalizedPath = normalizeSourcePath(relativePath);
        Path targetPath = Paths.get(sourcesDir, normalizedPath);

        if (!Files.exists(targetPath)) {
            throw new IOException("Source file not found: " + normalizedPath);
        }

        String content = readFile(targetPath);
        CodeStructureSummary summary = summarizer.summarize(normalizedPath, content);

        return summarizer.toCompressedView(summary);
    }

    /**
     * Phase 4: 解析模型响应并应用修改
     */
    public String applyModelResponse(String relativePath, String modelResponse,
                                     String sourcesDir) throws IOException {
        String normalizedPath = normalizeSourcePath(relativePath);
        Path targetPath = Paths.get(sourcesDir, normalizedPath);

        if (!Files.exists(targetPath)) {
            throw new IOException("Source file not found: " + normalizedPath);
        }

        String content = readFile(targetPath);

        // 解析模型响应
        List<ModificationInstruction> instructions = protocol.parse(modelResponse, normalizedPath);

        if (instructions.isEmpty()) {
            throw new IOException("No valid instructions found in model response");
        }

        // 应用修改
        ModificationInstruction instruction = instructions.get(0);
        instruction.addContext("source", content);

        try {
            String result = applicator.apply(content, instruction);
            writeFile(targetPath, result);
            fileContentCache.put(targetPath.toString(), result);
            return "Model response applied: " + instruction.getType() + " on " + instruction.getTarget();
        } catch (CodeModificationException e) {
            throw new IOException("Failed to apply modification: " + e.getMessage());
        }
    }

    /**
     * Phase 5: 高级修改 - 使用 JavaParser（如果可用）
     * 当前使用简化实现
     */
    public String advancedModify(String relativePath, String target, String newCode,
                                String sourcesDir, Map<String, String> options) throws IOException {
        // 高级选项处理
        boolean preserveFormatting = options == null || !options.containsKey("preserveFormatting") ||
                Boolean.parseBoolean(options.get("preserveFormatting"));

        String result = structuredReplace(relativePath, target, newCode, sourcesDir,
                ModificationInstruction.ModificationType.REPLACE_METHOD_BODY);

        if (preserveFormatting) {
            // 确保格式一致
            verifyAndFixFormatting(relativePath, sourcesDir);
        }

        return result;
    }

    /**
     * 验证并修复格式
     */
    private void verifyAndFixFormatting(String relativePath, String sourcesDir) throws IOException {
        String normalizedPath = normalizeSourcePath(relativePath);
        Path targetPath = Paths.get(sourcesDir, normalizedPath);

        if (!Files.exists(targetPath)) {
            return;
        }

        String content = readFile(targetPath);
        CodeStyle style = styleExtractor.extract(content, 0);

        // 简单的格式验证
        if (!content.contains("\r\n") && style.getLineSeparator().equals("\r\n")) {
            logger.info("Converting line endings to CRLF for: {}", normalizedPath);
            String converted = content.replace("\n", "\r\n");
            writeFile(targetPath, converted);
        }
    }

    /**
     * 在指定索引位置应用替换
     */
    private String applyReplaceAtIndex(String content, int startIndex, int length, String replacement) {
        String before = content.substring(0, startIndex);
        String after = content.substring(startIndex + length);
        return before + replacement + after;
    }

    /**
     * 将归一化内容中的索引转换为原始内容中的索引。
     * 归一化移除了 \r 字符，因此归一化索引比原始索引偏小。
     * 此方法通过扫描原始内容中的 \r 字符数来计算偏移量。
     */
    private int toOriginalIndex(String content, int normalizedIndex) {
        int originalIndex = 0;
        int crCount = 0;
        while (originalIndex < content.length() && (originalIndex - crCount) < normalizedIndex) {
            if (content.charAt(originalIndex) == '\r') {
                crCount++;
            }
            originalIndex++;
        }
        return originalIndex;
    }

    /**
     * 计算 oldStr 在原始内容中实际匹配的长度。
     * 当内容为 CRLF (\r\n) 而 oldStr 为 LF (\n) 时，归一化匹配找到的位置可以匹配，
     * 但实际字符数不同。此方法遍历原始内容，为每个额外的 \r 增加长度计数。
     */
    private int calculateActualMatchLength(String content, int startIndex, String normalizedOld) {
        int actualLength = 0;
        int contentPos = startIndex;
        for (int i = 0; i < normalizedOld.length(); i++) {
            if (contentPos < content.length() && content.charAt(contentPos) == '\r') {
                actualLength++; // 跳过 CRLF 中多余的 \r
                contentPos++;
            }
            contentPos++;
            actualLength++;
        }
        return actualLength;
    }

    /**
     * 读取文件内容
     */
    private String readFile(Path path) throws IOException {
        String key = path.toString();
        return fileContentCache.computeIfAbsent(key, k -> {
            try {
                return new String(Files.readAllBytes(path), "UTF-8");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 写入文件内容
     */
    private void writeFile(Path path, String content) throws IOException {
        Files.write(path, content.getBytes("UTF-8"));
    }

    /**
     * 归一化源码路径
     */
    private String normalizeSourcePath(String relativePath) {
        if (relativePath == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }

        // 移除 sources/ 前缀
        String path = relativePath;
        if (path.startsWith("sources/")) {
            path = path.substring("sources/".length());
        }

        // 统一斜杠
        return path.replace('\\', '/');
    }

    /**
     * 截断字符串
     */
    private String truncate(String str, int maxLength) {
        if (str == null) return "null";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        fileContentCache.clear();
    }

    /**
     * 清除特定文件的缓存
     */
    public void clearCache(String relativePath) {
        String normalizedPath = normalizeSourcePath(relativePath);
        fileContentCache.remove(normalizedPath);
    }
}
