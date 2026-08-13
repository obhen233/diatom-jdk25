package com.github.obhen233.core.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 代码修改系统 - 主入口
 *
 * 提供简化的 API 来执行结构化代码修改
 */
public class CodeModificationSystem {
    private static final Logger logger = LoggerFactory.getLogger(CodeModificationSystem.class);

    private final CodeStructureSummarizer summarizer;
    private final SemanticLocator locator;
    private final CodeStyleExtractor styleExtractor;
    private final ModificationApplicator applicator;
    private final CodeModificationProtocol protocol;

    public CodeModificationSystem() {
        this.summarizer = new CodeStructureSummarizer();
        this.locator = new SemanticLocator();
        this.styleExtractor = new CodeStyleExtractor();
        this.applicator = new ModificationApplicator();
        this.protocol = new CodeModificationProtocol();
    }

    /**
     * 获取文件的结构化摘要
     */
    public CodeStructureSummary getSummary(String filePath) throws IOException {
        String sourceCode = readFile(filePath);
        return summarizer.summarize(filePath, sourceCode);
    }

    /**
     * 获取文件的压缩视图（用于发送给模型）
     */
    public String getCompressedView(String filePath) throws IOException {
        CodeStructureSummary summary = getSummary(filePath);
        return summarizer.toCompressedView(summary);
    }

    /**
     * 获取模型友好的 JSON 表示
     */
    public String getModelJson(String filePath) throws IOException {
        CodeStructureSummary summary = getSummary(filePath);
        return summarizer.toModelJson(summary);
    }

    /**
     * 生成修改提示词
     */
    public String generateModificationPrompt(String task, String filePath) throws IOException {
        CodeStructureSummary summary = getSummary(filePath);
        return protocol.generatePrompt(task, summary);
    }

    /**
     * 应用模型返回的修改指令
     */
    public String applyModification(String filePath, String modelResponse) throws IOException {
        String sourceCode = readFile(filePath);

        // 解析模型响应
        List<ModificationInstruction> instructions = protocol.parse(modelResponse, filePath);

        if (instructions.isEmpty()) {
            throw new CodeModificationException("No valid modification instructions found in response");
        }

        // 应用第一个修改指令（简化处理）
        ModificationInstruction instruction = instructions.get(0);

        // 将源码放入 context
        instruction.addContext("source", sourceCode);

        // 应用修改
        String result = applicator.apply(sourceCode, instruction);

        // 写回文件
        writeFile(filePath, result);

        logger.info("Applied modification to {}: {}", filePath, instruction.getType());
        return result;
    }

    /**
     * 直接应用修改指令
     */
    public String applyModification(String filePath, ModificationInstruction instruction) throws IOException {
        String sourceCode = readFile(filePath);
        instruction.addContext("source", sourceCode);

        String result = applicator.apply(sourceCode, instruction);
        writeFile(filePath, result);

        logger.info("Applied modification to {}: {}", filePath, instruction.getType());
        return result;
    }

    /**
     * 验证修改是否成功
     */
    public boolean verifyModification(String filePath, String expectedPattern) throws IOException {
        String content = readFile(filePath);
        return content.contains(expectedPattern);
    }

    /**
     * 读取文件
     */
    private String readFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + filePath);
        }
        return new String(Files.readAllBytes(path), "UTF-8");
    }

    /**
     * 写入文件
     */
    private void writeFile(String filePath, String content) throws IOException {
        Path path = Paths.get(filePath);
        Files.write(path, content.getBytes("UTF-8"));
    }

    /**
     * 创建修改指令的便捷方法
     */
    public ModificationInstruction createReplaceMethodBodyInstruction(String filePath, String methodName, String newBody) {
        ModificationInstruction instruction = new ModificationInstruction();
        instruction.setFilePath(filePath);
        instruction.setType(ModificationInstruction.ModificationType.REPLACE_METHOD_BODY);
        instruction.setTarget(methodName);
        instruction.setNewCode(newBody);
        return instruction;
    }

    public ModificationInstruction createAddImportInstruction(String filePath, String importStatement) {
        ModificationInstruction instruction = new ModificationInstruction();
        instruction.setFilePath(filePath);
        instruction.setType(ModificationInstruction.ModificationType.ADD_IMPORT);
        instruction.setNewCode(importStatement);
        return instruction;
    }

    public ModificationInstruction createAddMethodInstruction(String filePath, String className, String newMethod) {
        ModificationInstruction instruction = new ModificationInstruction();
        instruction.setFilePath(filePath);
        instruction.setType(ModificationInstruction.ModificationType.ADD_METHOD);
        instruction.setTarget(className);
        instruction.setNewCode(newMethod);
        return instruction;
    }

    /**
     * 生成使用示例
     */
    public static void main(String[] args) throws Exception {
        CodeModificationSystem system = new CodeModificationSystem();

        // 示例 1: 获取文件摘要
        String filePath = "src/main/java/com/github/obhen233/core/tool/ToolRegistryCenter.java";

        System.out.println("=== Code Structure Summary ===");
        try {
            CodeStructureSummary summary = system.getSummary(filePath);
            System.out.println(summary);
            System.out.println("\n=== Compressed View ===");
            System.out.println(system.getCompressedView(filePath));
            System.out.println("\n=== Model JSON ===");
            System.out.println(system.getModelJson(filePath));
        } catch (IOException e) {
            System.out.println("File not found, skipping example output");
        }

        // 示例 2: 生成修改提示词
        System.out.println("\n=== Modification Prompt ===");
        try {
            String prompt = system.generateModificationPrompt(
                    "Add DatabaseToolsProvider registration to createStandard method",
                    filePath);
            System.out.println(prompt);
        } catch (IOException e) {
            System.out.println("File not found, skipping example output");
        }
    }
}
