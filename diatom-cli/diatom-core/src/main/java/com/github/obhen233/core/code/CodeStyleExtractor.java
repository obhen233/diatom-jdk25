package com.github.obhen233.core.code;

/**
 * 代码风格提取器
 */
public class CodeStyleExtractor {

    /**
     * 提取指定行附近的代码风格
     */
    public CodeStyle extract(String source, int aroundLine) {
        CodeStyle style = new CodeStyle();
        String[] lines = source.split("\n|\r\n|\r");

        // 1. 检测行尾符
        style.setLineSeparator(detectLineSeparator(source));

        // 2. 检测缩进
        int lineIndex = Math.min(aroundLine, Math.max(0, lines.length - 1));
        style.setIndent(detectIndent(lines[lineIndex]));

        // 3. 检测大括号风格
        style.setBraceStyle(detectBraceStyle(lines, lineIndex));

        return style;
    }

    /**
     * 检测行尾符
     */
    public String detectLineSeparator(String source) {
        if (source.contains("\r\n")) {
            return "\r\n";
        }
        if (source.contains("\r")) {
            return "\r";
        }
        return "\n";
    }

    /**
     * 检测缩进风格
     */
    public String detectIndent(String line) {
        if (line == null || line.isEmpty()) {
            return "    ";
        }

        // 检查tab缩进
        int tabCount = 0;
        for (char c : line.toCharArray()) {
            if (c == '\t') {
                tabCount++;
            } else if (c == ' ') {
                // 如果遇到空格，停止计数
                break;
            } else {
                break;
            }
        }

        if (tabCount > 0) {
            return "\t";
        }

        // 检测空格缩进（常见的是4空格或2空格）
        int spaceCount = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') {
                spaceCount++;
            } else {
                break;
            }
        }

        // 如果没有缩进或缩进是4的倍数，使用4空格
        if (spaceCount == 0 || spaceCount % 4 == 0) {
            return "    ";
        }
        // 否则使用2空格
        return "  ";
    }

    /**
     * 检测大括号风格
     */
    public CodeStyle.BraceStyle detectBraceStyle(String[] lines, int aroundLine) {
        int start = Math.max(0, aroundLine - 10);
        int end = Math.min(lines.length, aroundLine + 10);

        for (int i = start; i < end; i++) {
            String line = lines[i].trim();

            // K&R 风格: if () {
            if (line.endsWith("{") && !line.equals("{")) {
                return CodeStyle.BraceStyle.K_R;
            }

            // Allman 风格: 单独一行的 {
            if (line.equals("{") || line.startsWith("{")) {
                return CodeStyle.BraceStyle.ALLMAN;
            }
        }

        return CodeStyle.BraceStyle.K_R;  // 默认 K&R
    }

    /**
     * 检测包名
     */
    public String extractPackageName(String source) {
        if (source == null) return "";

        String[] lines = source.split("\n|\r\n|\r");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ")) {
                // 提取 package xxx; 中的 xxx
                int start = "package ".length();
                int end = trimmed.indexOf(';');
                if (end > start) {
                    return trimmed.substring(start, end).trim();
                }
            }
        }
        return "";
    }

    /**
     * 检测文件编码（简单实现）
     */
    public String detectEncoding(String source) {
        if (source != null && source.contains("\r\n")) {
            return "Windows (CRLF)";
        } else if (source != null && source.contains("\r")) {
            return "Mac (CR)";
        }
        return "Unix (LF)";
    }
}
