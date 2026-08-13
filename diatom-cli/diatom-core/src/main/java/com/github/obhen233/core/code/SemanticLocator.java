package com.github.obhen233.core.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义定位器：通过类名/方法名精确定位源码位置
 */
public class SemanticLocator {
    private static final Logger logger = LoggerFactory.getLogger(SemanticLocator.class);

    private final CodeStyleExtractor styleExtractor;

    public SemanticLocator() {
        this.styleExtractor = new CodeStyleExtractor();
    }

    /**
     * 定位方法体
     */
    public SourceLocation locateMethod(String filePath, String className,
                                       String methodName, String source) {
        SourceLocation location = new SourceLocation();
        location.setFilePath(filePath);
        location.setClassName(className);
        location.setMethodName(methodName);

        // 1. 定位类
        int classLine = findClassLine(source, className);
        if (classLine < 0) {
            logger.warn("Cannot find class {} in {}", className, filePath);
            return null;
        }
        location.setStartLine(classLine);

        // 2. 定位方法
        MethodRange methodRange = findMethodRange(source, methodName, classLine);
        if (methodRange != null) {
            location.setStartLine(methodRange.startLine);
            location.setEndLine(methodRange.endLine);
            location.setStartColumn(methodRange.startColumn);
            location.setEndColumn(methodRange.endColumn);
        } else {
            logger.warn("Cannot find method {} in class {} at line {}", methodName, className, classLine);
            return null;
        }

        // 3. 提取格式风格
        location.setStyle(styleExtractor.extract(source, methodRange.startLine));

        return location;
    }

    /**
     * 定位类定义
     */
    public SourceLocation locateClass(String filePath, String className, String source) {
        SourceLocation location = new SourceLocation();
        location.setFilePath(filePath);
        location.setClassName(className);

        int classLine = findClassLine(source, className);
        if (classLine < 0) {
            return null;
        }

        location.setStartLine(classLine);

        // 查找类结束的右大括号
        int endLine = findClassEndLine(source, classLine);
        location.setEndLine(endLine);

        // 提取格式风格
        location.setStyle(styleExtractor.extract(source, classLine));

        return location;
    }

    /**
     * 查找类定义行
     */
    private int findClassLine(String source, String className) {
        // 匹配: public class ClassName, class ClassName, interface ClassName, enum ClassName
        Pattern pattern = Pattern.compile(
                "(?:public\\s+)?(?:abstract\\s+)?(?:class|interface|enum)\\s+" + Pattern.quote(className) + "\\s*(?:extends\\s+\\w+\\s*)?(?:implements[^{]*)?\\{?",
                Pattern.MULTILINE);
        Matcher m = pattern.matcher(source);
        if (m.find()) {
            // 计算行号
            return source.substring(0, m.start()).split("\n|\r\n|\r").length;
        }
        return -1;
    }

    /**
     * 查找类的结束行（通过匹配大括号）
     */
    private int findClassEndLine(String source, int classStartLine) {
        String[] lines = source.split("\n|\r\n|\r");
        int braceCount = 0;
        boolean inClass = false;

        for (int i = classStartLine; i < lines.length; i++) {
            String line = lines[i];
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    braceCount++;
                    inClass = true;
                } else if (c == '}') {
                    braceCount--;
                }
            }
            if (inClass && braceCount == 0) {
                return i;
            }
        }
        return lines.length - 1;
    }

    /**
     * 查找方法体的范围
     */
    private MethodRange findMethodRange(String source, String methodName, int fromLine) {
        String[] lines = source.split("\n|\r\n|\r");

        // 正则匹配方法签名
        Pattern methodPattern = Pattern.compile(
                "(?:public|protected|private)?\\s*(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?(?:\\w+(?:<[^>]+>)?(?:\\s+...)?)\\s+" +
                        Pattern.quote(methodName) + "\\s*\\([^)]*\\)\\s*(?:throws[^{]+)?\\{?",
                Pattern.MULTILINE);

        Matcher matcher = methodPattern.matcher(source);
        int methodStartOffset = -1;

        // 找到在 fromLine 之后的方法
        while (matcher.find()) {
            int line = source.substring(0, matcher.start()).split("\n|\r\n|\r").length;
            if (line >= fromLine) {
                methodStartOffset = matcher.start();
                break;
            }
        }

        if (methodStartOffset < 0) {
            return null;
        }

        // 计算方法的起始行列
        String beforeMethod = source.substring(0, methodStartOffset);
        int startLine = beforeMethod.split("\n|\r\n|\r").length;
        int startColumn = beforeMethod.length() - beforeMethod.lastIndexOf('\n') - 1;

        // 查找方法体的结束位置
        int braceCount = 0;
        boolean methodStarted = false;
        int currentOffset = methodStartOffset;

        for (int i = methodStartOffset; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                braceCount++;
                methodStarted = true;
            } else if (c == '}') {
                braceCount--;
                if (methodStarted && braceCount == 0) {
                    // 方法结束
                    String beforeEnd = source.substring(0, i);
                    int endLine = beforeEnd.split("\n|\r\n|\r").length;
                    int endColumn = i - beforeEnd.lastIndexOf('\n') - 1;
                    return new MethodRange(startLine, startColumn, endLine, endColumn);
                }
            }
        }

        return null;
    }

    /**
     * 查找import语句的位置（用于添加新的import）
     */
    public int findImportInsertLine(String source) {
        String[] lines = source.split("\n|\r\n|\r");
        int lastImportLine = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("package ")) {
                continue;
            }
            if (line.startsWith("import ")) {
                lastImportLine = i;
            } else if (!line.isEmpty() && !line.startsWith("//") && !line.startsWith("/*")) {
                // 遇到第一个非import、非注释、非空行，插入点在其之前
                break;
            }
        }

        if (lastImportLine >= 0) {
            return lastImportLine + 1;
        }
        return 0;
    }

    /**
     * 辅助类：方法范围
     */
    private static class MethodRange {
        final int startLine;
        final int startColumn;
        final int endLine;
        final int endColumn;

        MethodRange(int startLine, int startColumn, int endLine, int endColumn) {
            this.startLine = startLine;
            this.startColumn = startColumn;
            this.endLine = endLine;
            this.endColumn = endColumn;
        }
    }
}
