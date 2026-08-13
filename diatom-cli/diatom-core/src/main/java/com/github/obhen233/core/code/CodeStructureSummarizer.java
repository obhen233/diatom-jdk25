package com.github.obhen233.core.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成代码的结构化摘要
 */
public class CodeStructureSummarizer {
    private static final Logger logger = LoggerFactory.getLogger(CodeStructureSummarizer.class);

    private final CodeStyleExtractor styleExtractor;

    public CodeStructureSummarizer() {
        this.styleExtractor = new CodeStyleExtractor();
    }

    /**
     * 生成代码文件的结构化摘要
     */
    public CodeStructureSummary summarize(String filePath, String sourceCode) {
        CodeStructureSummary summary = new CodeStructureSummary();
        summary.setFilePath(filePath);

        // 1. 提取 package
        summary.setPackageName(styleExtractor.extractPackageName(sourceCode));

        // 2. 提取类信息
        ClassInfo classInfo = extractClassInfo(sourceCode);
        summary.setClassName(classInfo.name);
        summary.setClassType(classInfo.type);
        summary.setApproximateLine(classInfo.line);

        // 3. 提取成员摘要
        summary.setMembers(extractMembers(sourceCode, classInfo.line));

        // 4. 提取 imports
        summary.setImports(extractImports(sourceCode));

        return summary;
    }

    /**
     * 转换为压缩视图（发送给模型）
     */
    public String toCompressedView(CodeStructureSummary summary) {
        StringBuilder sb = new StringBuilder();

        // package
        sb.append("package ").append(summary.getPackageName()).append(";\n");

        // imports
        for (CodeStructureSummary.ImportInfo imp : summary.getImports()) {
            sb.append("import ");
            if (imp.isStatic()) sb.append("static ");
            sb.append(imp.getPackageName());
            if (imp.isWildcard()) sb.append(".*");
            sb.append(";\n");
        }

        sb.append("\n");

        // class declaration
        sb.append(summary.getClassType()).append(" ").append(summary.getClassName()).append(" {\n");

        // members (with line numbers as hints)
        for (CodeStructureSummary.MemberSummary member : summary.getMembers()) {
            sb.append("  // [line:").append(member.getApproximateLine()).append("] ");
            sb.append(member.getType()).append(" ").append(member.getName());
            if (member.getSignature() != null) {
                sb.append(member.getSignature());
            }
            sb.append("\n");
        }

        sb.append("}");

        return sb.toString();
    }

    /**
     * 转换为给模型的结构化JSON
     */
    public String toModelJson(CodeStructureSummary summary) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"filePath\": \"").append(escapeJson(summary.getFilePath())).append("\",\n");
        json.append("  \"package\": \"").append(escapeJson(summary.getPackageName())).append("\",\n");
        json.append("  \"class\": \"").append(escapeJson(summary.getClassName())).append("\",\n");
        json.append("  \"classType\": \"").append(escapeJson(summary.getClassType())).append("\",\n");
        json.append("  \"members\": [\n");

        for (int i = 0; i < summary.getMembers().size(); i++) {
            CodeStructureSummary.MemberSummary m = summary.getMembers().get(i);
            json.append("    {\"name\": \"").append(escapeJson(m.getName()))
                    .append("\", \"type\": \"").append(escapeJson(m.getType()))
                    .append("\", \"line\": ").append(m.getApproximateLine());
            if (m.getSignature() != null) {
                json.append(", \"sig\": \"").append(escapeJson(m.getSignature())).append("\"");
            }
            json.append("}");
            if (i < summary.getMembers().size() - 1) json.append(",");
            json.append("\n");
        }

        json.append("  ],\n");
        json.append("  \"imports\": [\n");

        for (int i = 0; i < summary.getImports().size(); i++) {
            CodeStructureSummary.ImportInfo imp = summary.getImports().get(i);
            json.append("    \"").append(escapeJson(imp.toString())).append("\"");
            if (i < summary.getImports().size() - 1) json.append(",");
            json.append("\n");
        }

        json.append("  ]\n");
        json.append("}");

        return json.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 提取类信息
     */
    private ClassInfo extractClassInfo(String source) {
        ClassInfo info = new ClassInfo();

        // 匹配 class/interface/enum 定义
        Pattern pattern = Pattern.compile(
                "(?:public\\s+)?(?:abstract\\s+)?(?:class|interface|enum)\\s+(\\w+)",
                Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(source);

        if (matcher.find()) {
            String declaration = matcher.group(0).trim();
            if (declaration.startsWith("class")) {
                info.type = "class";
            } else if (declaration.startsWith("interface")) {
                info.type = "interface";
            } else if (declaration.startsWith("enum")) {
                info.type = "enum";
            }

            info.name = matcher.group(1);
            info.line = source.substring(0, matcher.start()).split("\n|\r\n|\r").length;
        }

        return info;
    }

    /**
     * 提取成员摘要
     */
    private List<CodeStructureSummary.MemberSummary> extractMembers(String source, int classStartLine) {
        List<CodeStructureSummary.MemberSummary> members = new ArrayList<>();
        String[] lines = source.split("\n|\r\n|\r");

        // 方法和字段的正则
        Pattern fieldPattern = Pattern.compile(
                "(?:public|protected|private)?\\s*(?:static\\s+)?(?:final\\s+)?(?:transient\\s+)?(?:volatile\\s+)?\\w+(?:<[^>]+>)?(?:\\s+...)?\\s+(\\w+)\\s*(?:=|;)",
                Pattern.MULTILINE);

        Pattern methodPattern = Pattern.compile(
                "(?:public|protected|private)?\\s*(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?\\w+(?:<[^>]+>)?\\s+(\\w+)\\s*\\([^)]*\\)",
                Pattern.MULTILINE);

        // 简单方法：匹配所有疑似方法声明
        Pattern simpleMethod = Pattern.compile(
                "(?:public|protected|private)\\s+(?:static\\s+)?[\\w<>,\\s]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{?",
                Pattern.MULTILINE);

        // 先用简单方法找到所有候选
        Matcher methodMatcher = simpleMethod.matcher(source);
        int lastLine = classStartLine;

        while (methodMatcher.find()) {
            int line = source.substring(0, methodMatcher.start()).split("\n|\r\n|\r").length;
            if (line <= classStartLine) continue;

            String match = methodMatcher.group(0);
            String methodName = methodMatcher.group(1);

            // 跳过构造函数（与类名同名）
            // 跳过 getter/setter 等简单方法
            if (match.contains("(") && match.contains(")")) {
                CodeStructureSummary.MemberSummary m = new CodeStructureSummary.MemberSummary();
                m.setName(methodName);
                m.setType("METHOD");
                m.setApproximateLine(line);
                m.setSignature(extractSignature(match));
                members.add(m);
                lastLine = line;
            }
        }

        return members;
    }

    private String extractSignature(String methodDecl) {
        // 简化：只返回参数部分
        int parenStart = methodDecl.indexOf('(');
        int parenEnd = methodDecl.indexOf(')');
        if (parenStart >= 0 && parenEnd > parenStart) {
            String params = methodDecl.substring(parenStart, parenEnd + 1);
            return params;
        }
        return "()";
    }

    /**
     * 提取import语句
     */
    private List<CodeStructureSummary.ImportInfo> extractImports(String source) {
        List<CodeStructureSummary.ImportInfo> imports = new ArrayList<>();
        String[] lines = source.split("\n|\r\n|\r");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ")) {
                String importStr = trimmed.substring(7).trim();
                boolean isStatic = false;
                boolean isWildcard = false;

                if (importStr.startsWith("static ")) {
                    isStatic = true;
                    importStr = importStr.substring(7).trim();
                }
                if (importStr.endsWith(".*")) {
                    isWildcard = true;
                    importStr = importStr.substring(0, importStr.length() - 2);
                }
                if (importStr.endsWith(";")) {
                    importStr = importStr.substring(0, importStr.length() - 1);
                }

                CodeStructureSummary.ImportInfo info = new CodeStructureSummary.ImportInfo(importStr, isStatic, isWildcard);
                imports.add(info);
            } else if (trimmed.startsWith("package ") || trimmed.isEmpty() ||
                    trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            } else {
                // 遇到非import语句，停止
                break;
            }
        }

        return imports;
    }

    private static class ClassInfo {
        String name = "";
        String type = "class";
        int line = 0;
    }
}
