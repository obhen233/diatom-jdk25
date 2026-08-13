package com.github.obhen233.core.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码修改协议 - 解析模型返回的修改指令
 */
public class CodeModificationProtocol {
    private static final Logger logger = LoggerFactory.getLogger(CodeModificationProtocol.class);

    /**
     * 解析模型返回的修改指令
     * 支持两种格式：
     * 1. 结构化 JSON
     * 2. 简化文本格式
     */
    public List<ModificationInstruction> parse(String modelResponse, String defaultFilePath) {
        List<ModificationInstruction> instructions = new ArrayList<>();

        // 尝试 JSON 格式
        try {
            if (modelResponse.trim().startsWith("{")) {
                List<ModificationInstruction> parsed = parseJson(modelResponse, defaultFilePath);
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            logger.debug("JSON parsing failed, trying text format: {}", e.getMessage());
        }

        // 尝试文本格式
        return parseTextFormat(modelResponse, defaultFilePath);
    }

    /**
     * 解析 JSON 格式的修改指令
     */
    private List<ModificationInstruction> parseJson(String json, String defaultFilePath) {
        List<ModificationInstruction> instructions = new ArrayList<>();

        // 简化解析：提取关键字段
        // {"type": "REPLACE_METHOD_BODY", "target": "methodName", "newCode": "...", "file": "..."}

        // 提取 type
        String type = extractJsonField(json, "type");
        // 提取 target
        String target = extractJsonField(json, "target");
        // 提取 newCode
        String newCode = extractJsonField(json, "newCode");
        // 提取 file
        String file = extractJsonField(json, "file", defaultFilePath);

        if (type != null && target != null) {
            ModificationInstruction instruction = new ModificationInstruction();
            instruction.setFilePath(file);
            instruction.setType(parseModificationType(type));
            instruction.setTarget(target);
            instruction.setNewCode(newCode != null ? newCode : "");
            instructions.add(instruction);
        }

        return instructions;
    }

    /**
     * 解析文本格式的修改指令
     *
     * 格式示例：
     * MODIFY: file.java
     * TYPE: REPLACE_METHOD_BODY
     * TARGET: ClassName.methodName
     * NEW_CODE:
     * {
     *   // new code here
     * }
     */
    private List<ModificationInstruction> parseTextFormat(String response, String defaultFilePath) {
        List<ModificationInstruction> instructions = new ArrayList<>();

        String[] sections = response.split("---");

        for (String section : sections) {
            section = section.trim();
            if (section.isEmpty()) continue;

            ModificationInstruction instruction = new ModificationInstruction();
            instruction.setFilePath(defaultFilePath);

            String[] lines = section.split("\n");

            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("TYPE:")) {
                    instruction.setType(parseModificationType(line.substring(5).trim()));
                } else if (line.startsWith("TARGET:")) {
                    instruction.setTarget(line.substring(7).trim());
                } else if (line.startsWith("FILE:")) {
                    instruction.setFilePath(line.substring(6).trim());
                } else if (line.startsWith("NEW_CODE:") || line.startsWith("CODE:")) {
                    // 收集后续行作为新代码
                    int codeStart = section.indexOf(line) + line.length();
                    String code = section.substring(codeStart).trim();
                    // 移除可能的 ``` 包裹
                    code = code.replaceAll("^```\\w*", "").replaceAll("```$", "").trim();
                    instruction.setNewCode(code);
                }
            }

            if (instruction.getType() != null && instruction.getTarget() != null) {
                instructions.add(instruction);
            }
        }

        return instructions;
    }

    /**
     * 解析修改类型
     */
    private ModificationInstruction.ModificationType parseModificationType(String type) {
        type = type.toUpperCase().replace("-", "_").replace(" ", "_");

        switch (type) {
            case "REPLACE_METHOD_BODY":
            case "REPLACEMETHODBODY":
                return ModificationInstruction.ModificationType.REPLACE_METHOD_BODY;
            case "ADD_METHOD":
            case "ADDMETHOD":
                return ModificationInstruction.ModificationType.ADD_METHOD;
            case "ADD_FIELD":
            case "ADDFIELD":
                return ModificationInstruction.ModificationType.ADD_FIELD;
            case "ADD_IMPORT":
            case "ADDIMPORT":
                return ModificationInstruction.ModificationType.ADD_IMPORT;
            case "MODIFY_CLASS_BODY":
            case "MODIFYCLASSBODY":
                return ModificationInstruction.ModificationType.MODIFY_CLASS_BODY;
            case "INSERT_STATEMENT":
            case "INSERTSTATEMENT":
                return ModificationInstruction.ModificationType.INSERT_STATEMENT;
            case "REPLACE_FILE":
            case "REPLACEFILE":
                return ModificationInstruction.ModificationType.REPLACE_FILE;
            default:
                logger.warn("Unknown modification type: {}, defaulting to REPLACE_METHOD_BODY", type);
                return ModificationInstruction.ModificationType.REPLACE_METHOD_BODY;
        }
    }

    /**
     * 从 JSON 字符串中提取字段值
     */
    private String extractJsonField(String json, String field) {
        return extractJsonField(json, field, null);
    }

    private String extractJsonField(String json, String field, String defaultValue) {
        // 简单的字段提取，不依赖 JSON 库
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return unescapeJson(matcher.group(1));
        }

        // 尝试多行字段值
        pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
        matcher = pattern.matcher(json);
        if (matcher.find()) {
            return unescapeJson(matcher.group(1));
        }

        return defaultValue;
    }

    /**
     * 反转义 JSON 字符串
     */
    private String unescapeJson(String s) {
        if (s == null) return null;
        return s.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    /**
     * 生成模型请求的提示词
     */
    public String generatePrompt(String taskDescription, CodeStructureSummary summary) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("Task: ").append(taskDescription).append("\n\n");

        prompt.append("File: ").append(summary.getFilePath()).append("\n");
        prompt.append("Class: ").append(summary.getClassName()).append("\n\n");

        prompt.append("Code structure:\n");
        prompt.append(toCompressedView(summary)).append("\n\n");

        prompt.append("Instructions:\n");
        prompt.append("- Return modification in structured format:\n");
        prompt.append("- TYPE: REPLACE_METHOD_BODY / ADD_METHOD / ADD_FIELD / ADD_IMPORT\n");
        prompt.append("- TARGET: method name or class name\n");
        prompt.append("- FILE: file path (optional, use default if not specified)\n");
        prompt.append("- NEW_CODE: the new code to insert (use \\n for line breaks)\n");
        prompt.append("\nExample:\n");
        prompt.append("TYPE: ADD_IMPORT\n");
        prompt.append("TARGET: com.github.obhen233.config.AppConfig\n");
        prompt.append("NEW_CODE: import com.example.NewClass;\n");

        return prompt.toString();
    }

    /**
     * 生成压缩视图
     */
    private String toCompressedView(CodeStructureSummary summary) {
        StringBuilder sb = new StringBuilder();

        sb.append("package ").append(summary.getPackageName()).append(";\n");

        for (CodeStructureSummary.ImportInfo imp : summary.getImports()) {
            sb.append(imp.toString()).append("\n");
        }

        sb.append("\n");
        sb.append(summary.getClassType()).append(" ").append(summary.getClassName()).append(" {\n");

        for (CodeStructureSummary.MemberSummary m : summary.getMembers()) {
            sb.append("  // [").append(m.getApproximateLine()).append("] ");
            sb.append(m.getType()).append(" ").append(m.getName());
            if (m.getSignature() != null) {
                sb.append(m.getSignature());
            }
            sb.append("\n");
        }

        sb.append("}");

        return sb.toString();
    }
}
