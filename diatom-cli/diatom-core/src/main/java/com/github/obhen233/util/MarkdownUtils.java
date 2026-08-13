package com.github.obhen233.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for converting markdown text to plain text for terminal display.
 * Pure Java regex-based implementation — no extra dependencies.
 *
 * Processing order:
 * 1. Normalize line endings
 * 2. Extract fenced code blocks (replace with placeholders)
 * 3. Process tables (join cells with spaces)
 * 4. Strip horizontal rules
 * 5. Strip headings (keep text)
 * 6. Strip blockquotes (keep text)
 * 7. Strip list markers (keep indentation + text)
 * 8. Strip inline formatting (bold, italic, code, strikethrough)
 * 9. Strip links and images
 * 10. Clean up excessive whitespace (code blocks are still placeholders, safe)
 * 11. Restore code blocks from placeholders
 */
public class MarkdownUtils {

    private MarkdownUtils() {}

    // Placeholder pattern for extracted code blocks (uses null chars unlikely in real text)
    private static final String CODE_BLOCK_PLACEHOLDER = "\u0000CODE_BLOCK_%d\u0000";

    /**
     * Strip all markdown formatting from text, returning clean plain text.
     */
    public static String stripMarkdown(String text) {
        if (text == null || text.isEmpty()) return "";

        String result = text;

        // Step 1: Normalize line endings
        result = normalizeLineEndings(result);

        // Step 2: Extract fenced code blocks — keep content, remove backticks
        List<String> codeBlocks = new ArrayList<>();
        result = extractFencedCodeBlocks(result, codeBlocks);

        // Step 3: Parse tables — join cells with spaces
        result = processTables(result);

        // Step 4: Strip horizontal rules (lines that are ---, ***, ___, possibly with spaces)
        result = result.replaceAll("(?m)^[ \\t]*[-*_]{3,}[ \\t]*$", "");

        // Step 5: Strip headings (#, ##, etc.) — keep the text
        result = result.replaceAll("(?m)^[ \\t]*#{1,6}[ \\t]+", "");

        // Step 6: Strip blockquotes (> ) — keep the text
        result = result.replaceAll("(?m)^[ \\t]*>+[ \\t]?", "");

        // Step 7: Strip list markers (- , * , + , 1. , etc.) — keep indentation + text
        // Ordered list: "1. ", "1) ", "(1) " etc.
        result = result.replaceAll("(?m)^([ \\t]*)(?:[-*+]|[0-9]+\\.|[0-9]+\\)|\\([0-9]+\\))[ \\t]+", "$1");
        // Unordered list with asterisk that could be confused with bold
        result = result.replaceAll("(?m)^([ \\t]*)[*][ \\t]+", "$1  ");

        // Step 8: Strip inline formatting
        // Bold **text** → text
        result = result.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        // Italic *text* → text (must not match bold **)
        result = result.replaceAll("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", "$1");
        // Bold __text__ → text
        result = result.replaceAll("__(.+?)__", "$1");
        // Italic _text_ → text (must not match within words like variable_names)
        result = result.replaceAll("(?<![\\w])_(?!_)(.+?)(?<!_)_(?![\\w])", "$1");
        // Strikethrough ~~text~~ → text
        result = result.replaceAll("~~(.+?)~~", "$1");
        // Inline code `code` → code
        result = result.replaceAll("`([^`]+)`", "$1");

        // Step 9: Strip links [text](url) → text, images ![alt](url) → alt
        // Images first (images use same syntax as links but with ! prefix)
        result = result.replaceAll("!\\[([^\\]]*)\\]\\([^)]+\\)", "$1");
        // Standard links [text](url)
        result = result.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");
        // Reference-style links [text][ref]
        result = result.replaceAll("\\[([^\\]]+)\\]\\[[^\\]]*\\]", "$1");

        // Step 10: Clean up excessive whitespace — code blocks are still placeholders, safe
        result = cleanupWhitespace(result);

        // Step 11: Restore code blocks from placeholders
        result = restoreCodeBlocks(result, codeBlocks);

        return result.trim();
    }

    /**
     * Convert markdown tables into aligned text tables for terminal display.
     * Returns the input text with tables replaced by clean pipe-delimited output
     * with aligned columns for better readability.
     */
    public static String toTextTable(String text) {
        if (text == null || text.isEmpty()) return "";

        String result = normalizeLineEndings(text);
        StringBuilder sb = new StringBuilder();
        String[] lines = result.split("\n");

        // State for table parsing
        List<String[]> tableRows = new ArrayList<>();
        boolean inTable = false;
        int[] colWidths = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Check if this line looks like a table row: |...|
            if (isTableRow(line)) {
                String[] cells = parseTableRow(line);
                if (cells != null) {
                    // Check if this is a separator row (|---|---|)
                    if (isTableSeparator(cells)) {
                        continue; // skip separator row
                    }

                    // Initialize or update column widths
                    if (colWidths == null) {
                        colWidths = new int[cells.length];
                    } else {
                        int minLen = Math.min(colWidths.length, cells.length);
                        for (int j = 0; j < minLen; j++) {
                            colWidths[j] = Math.max(colWidths[j], cells[j].length());
                        }
                    }

                    tableRows.add(cells);
                    inTable = true;
                    continue;
                }
            }

            // If we were in a table, flush it now (table ended)
            if (inTable) {
                flushTable(sb, tableRows, colWidths);
                tableRows.clear();
                colWidths = null;
                inTable = false;
            }

            sb.append(line).append("\n");
        }

        // Flush remaining table at end of text
        if (inTable) {
            flushTable(sb, tableRows, colWidths);
        }

        return sb.toString();
    }

    /**
     * Legacy alias for stripMarkdown.
     */
    public static String toPlainText(String text) {
        return stripMarkdown(text);
    }

    // ========== Private Helpers ==========

    private static String normalizeLineEndings(String text) {
        String result = text;
        // Handle escaped newlines and tabs from JSON/AI output
        result = result.replace("\\r\\n", "\n");
        result = result.replace("\\r", "\n");
        result = result.replace("\\n", "\n");
        result = result.replace("\\t", "    ");
        // Normalize actual line endings
        result = result.replace("\r\n", "\n");
        result = result.replace("\r", "\n");
        return result;
    }

    /**
     * Extract fenced code blocks (```lang\n...\n```) and replace with placeholders.
     * The code content is stored in the provided list for later restoration.
     */
    private static String extractFencedCodeBlocks(String text, List<String> codeBlocks) {
        // Primary pattern: requires \n before closing ```
        // Supports: ```lang\ncontent\n```  and  ```lang\ncontent``` (no trailing newline)
        Pattern fencedCodePattern = Pattern.compile(
                "```(?:\\w+)?[ \t]*\\n([\\s\\S]+?)(?:\\n```|```(?:\\n|$))",
                Pattern.DOTALL
        );
        // Secondary pattern: handles  ```\ncontent\n```  (no language, no space before newline)
        // This is a subset of the primary pattern but kept as fallback for clarity
        StringBuffer sb = new StringBuffer();
        Matcher matcher = fencedCodePattern.matcher(text);
        while (matcher.find()) {
            String codeContent = matcher.group(1);
            int index = codeBlocks.size();
            codeBlocks.add(codeContent);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    String.format(CODE_BLOCK_PLACEHOLDER, index)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Convert markdown tables to plain text by joining cells with spaces.
     * This is used within stripMarkdown() for basic table → text conversion.
     */
    private static String processTables(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        boolean inTable = false;

        for (String line : lines) {
            if (isTableRow(line)) {
                String[] cells = parseTableRow(line);
                if (cells != null) {
                    // Skip separator rows (|---|---|)
                    if (isTableSeparator(cells)) {
                        continue;
                    }
                    // Join cells with spaces
                    StringBuilder plainLine = new StringBuilder();
                    for (int j = 0; j < cells.length; j++) {
                        if (j > 0) plainLine.append("  ");
                        plainLine.append(cells[j].trim());
                    }
                    sb.append(plainLine).append("\n");
                    inTable = true;
                    continue;
                }
            }

            // Add blank line after table for readability
            if (inTable) {
                sb.append("\n");
                inTable = false;
            }
            sb.append(line).append("\n");
        }

        return sb.toString();
    }

    private static boolean isTableRow(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2;
    }

    private static String[] parseTableRow(String line) {
        String trimmed = line.trim();
        // Remove leading and trailing |
        String inner = trimmed.substring(1, trimmed.length() - 1);
        String[] parts = inner.split("\\|");
        String[] cells = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            cells[i] = parts[i].trim();
        }
        return cells;
    }

    private static boolean isTableSeparator(String[] cells) {
        if (cells == null || cells.length == 0) return false;
        for (String cell : cells) {
            String trimmed = cell.trim();
            if (!trimmed.matches(":?-{3,}:?")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Render table rows as aligned pipe-delimited text.
     */
    private static void flushTable(StringBuilder sb, List<String[]> rows, int[] widths) {
        if (rows == null || rows.isEmpty()) return;
        if (widths == null) return;

        // Reset and recalculate column widths from all rows
        for (int i = 0; i < widths.length; i++) {
            widths[i] = 0;
        }
        for (String[] row : rows) {
            for (int j = 0; j < Math.min(row.length, widths.length); j++) {
                widths[j] = Math.max(widths[j], row[j].length());
            }
        }

        // Render each row as a formatted pipe-delimited line
        for (String[] row : rows) {
            sb.append("|");
            for (int j = 0; j < Math.min(row.length, widths.length); j++) {
                sb.append(" ").append(padRight(row[j], widths[j])).append(" |");
            }
            sb.append("\n");
        }
        sb.append("\n"); // blank line after table
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Clean up excessive whitespace. Called when code blocks are still
     * placeholders, so code content is safe from whitespace normalization.
     */
    private static String cleanupWhitespace(String text) {
        String result = text;
        // Remove trailing whitespace from each line
        result = result.replaceAll("(?m)[ \\t]+$", "");
        // Remove lines that are only whitespace
        result = result.replaceAll("(?m)^[ \\t]+$", "");
        // Collapse 3+ consecutive newlines to 2 (one blank line between paragraphs)
        result = result.replaceAll("\n{3,}", "\n\n");
        // Trim start/end whitespace WITHOUT stripping null chars used as placeholder markers.
        // Java's String.trim() strips any char <= ' ' (32), which includes \u0000 null chars.
        // Since code block placeholders use \u0000 as boundary markers, trim() would corrupt them.
        result = result.replaceAll("^[ \\t\\n\\r]+", "");
        result = result.replaceAll("[ \\t\\n\\r]+$", "");
        return result;
    }

    private static String restoreCodeBlocks(String text, List<String> codeBlocks) {
        for (int i = 0; i < codeBlocks.size(); i++) {
            String placeholder = String.format(CODE_BLOCK_PLACEHOLDER, i);
            text = text.replace(placeholder, codeBlocks.get(i));
        }
        return text;
    }
}
