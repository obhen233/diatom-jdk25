package com.github.obhen233.core.tool.builtin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级文件结构摘要生成器。
 * 支持多语言：Java/JS/TS/Vue/JSP/HTML/XML/YAML/Properties。
 * 不依赖 AST 解析器，纯正则实现，毫秒级。
 */
public class FileSummarizer {
    private static final Logger logger = LoggerFactory.getLogger(FileSummarizer.class);

    /** 最大读取行数 */
    private static final int MAX_LINES = 5000;

    /** 最大读取字节数 */
    private static final long MAX_BYTES = 512 * 1024; // 512KB

    /**
     * 对文件生成 L1 结构摘要。
     *
     * @param filePath 文件绝对路径
     * @return 格式化摘要文本
     */
    public String summarize(Path filePath, String depth) {
        if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
            return "Error: File not found or is a directory";
        }

        try {
            long size = Files.size(filePath);
            if (size > MAX_BYTES) {
                return String.format("Error: File too large (%d KB, max %d KB)", size / 1024, MAX_BYTES / 1024);
            }

            byte[] bytes = Files.readAllBytes(filePath);
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);

            if (lines.length > MAX_LINES) {
                String[] truncated = new String[MAX_LINES];
                System.arraycopy(lines, 0, truncated, 0, MAX_LINES);
                lines = truncated;
            }

            String filename = filePath.getFileName().toString();
            String ext = getExtension(filename);

            StringBuilder sb = new StringBuilder();
            sb.append("=== File Summary: ").append(filename).append(" (").append(lines.length).append(" lines, ")
              .append(formatSize(size)).append(") === [").append("l2".equals(depth) ? "L1+L2" : "L1").append("]").append(NEWLINE);

            switch (ext) {
                case ".java":
                    summarizeJava(sb, content, lines);
                    if ("l2".equals(depth)) {
                        summarizeJavaL2(sb, content, lines);
                    }
                    break;
                case ".js":
                case ".jsx":
                case ".ts":
                case ".tsx":
                    summarizeJs(sb, content, lines, ext);
                    break;
                case ".vue":
                    summarizeVue(sb, content, lines);
                    break;
                case ".jsp":
                    summarizeJsp(sb, content, lines);
                    break;
                case ".html":
                case ".htm":
                    summarizeHtml(sb, content, lines);
                    break;
                case ".xml":
                    summarizeXml(sb, content, lines);
                    break;
                case ".yml":
                case ".yaml":
                    summarizeYaml(sb, content, lines);
                    break;
                case ".properties":
                    summarizeProperties(sb, content, lines);
                    break;
                case ".css":
                case ".scss":
                case ".less":
                    summarizeCss(sb, content, lines);
                    break;
                case ".json":
                    summarizeJson(sb, content, lines);
                    break;
                default:
                    summarizeGeneric(sb, content, lines);
                    break;
            }

            return sb.toString();

        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    public String summarize(Path filePath) {
        return summarize(filePath, "l1");
    }

    // ========== Java ==========

    private static final Pattern JAVA_PACKAGE = Pattern.compile("^\\s*package\\s+([^;]+);");
    private static final Pattern JAVA_IMPORT = Pattern.compile("^\\s*import\\s+([^;]+);");
    private static final Pattern JAVA_CLASS_DECL = Pattern.compile(
        "^\\s*(public\\s+|protected\\s+|private\\s+)?(abstract\\s+|final\\s+)?(static\\s+)?(class|interface|enum|@interface)\\s+(\\w+)(?:<[^>]*>)?(?:\\s+extends\\s+([^{]+?))?(?:\\s+implements\\s+([^{]+?))?\\s*\\{");
    private static final Pattern JAVA_ANNOTATION = Pattern.compile("^\\s*@(\\w+)");
    private static final Pattern JAVA_METHOD = Pattern.compile(
        "^\\s*(public|protected|private|static|final|abstract|synchronized|native|default|\\w+\\s+)+\\s*(<[^>]+>\\s*)?(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[^{]+)?(?:\\s*\\{|;)");
    private static final Pattern JAVA_FIELD = Pattern.compile(
        "^\\s*(private|protected|public)?\\s*(static\\s+|final\\s+|volatile\\s+|transient\\s+)*(\\w+(?:<[^>]*>)?(?:\\[\\])?)\\s+(\\w+)\\s*(?:=|;)");
    private static final Pattern JAVA_CONSTRUCTOR = Pattern.compile(
        "^\\s*(public|protected|private)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:\\s*\\{)");

    private void summarizeJava(StringBuilder sb, String content, String[] lines) {
        // Package
        Matcher pkgMatcher = JAVA_PACKAGE.matcher(lines[0]);
        if (pkgMatcher.find()) {
            sb.append("package ").append(pkgMatcher.group(1)).append(NEWLINE);
        } else {
            for (String line : lines) {
                pkgMatcher = JAVA_PACKAGE.matcher(line);
                if (pkgMatcher.find()) {
                    sb.append("package ").append(pkgMatcher.group(1)).append(NEWLINE);
                    break;
                }
            }
        }

        // Imports (collect)
        List<String> imports = new ArrayList<>();
        for (String line : lines) {
            Matcher m = JAVA_IMPORT.matcher(line);
            if (m.find()) {
                imports.add(m.group(1));
            }
        }
        if (!imports.isEmpty()) {
            sb.append("imports: ").append(imports.size()).append(NEWLINE);
            if (imports.size() <= 8) {
                for (String imp : imports) {
                    sb.append("  ├── ").append(imp).append(NEWLINE);
                }
            } else {
                for (int i = 0; i < 8; i++) {
                    sb.append("  ├── ").append(imports.get(i)).append(NEWLINE);
                }
                sb.append("  └── ... and ").append(imports.size() - 8).append(" more").append(NEWLINE);
            }
        }

        // Class/interface declarations
        sb.append(NEWLINE);
        boolean hasClass = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher cm = JAVA_CLASS_DECL.matcher(line);
            if (cm.find()) {
                hasClass = true;
                String modifiers = cm.group(1) != null ? cm.group(1).trim() + " " : "";
                modifiers += cm.group(2) != null ? cm.group(2).trim() + " " : "";
                modifiers += cm.group(3) != null ? cm.group(3).trim() + " " : "";
                String type = cm.group(4);
                String name = cm.group(5);
                String extendz = cm.group(6);
                String implementz = cm.group(7);

                sb.append(appendAnnotation(lines, i));
                sb.append(modifiers).append(type).append(" ").append(name);
                if (extendz != null && !extendz.trim().isEmpty()) {
                    sb.append(NEWLINE).append("  extends ").append(extendz.trim());
                }
                if (implementz != null && !implementz.trim().isEmpty()) {
                    sb.append(NEWLINE).append("  implements ").append(implementz.trim());
                }
                sb.append(NEWLINE);
                sb.append("  ── at line ").append(i + 1).append(NEWLINE);
            }
        }
        if (!hasClass) {
            sb.append("(no class/interface declaration found)").append(NEWLINE);
        }

        // Methods and fields
        sb.append(NEWLINE).append("  ── Members ──").append(NEWLINE);

        boolean hasMembers = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Skip comments, annotations, empty
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
                || trimmed.startsWith("@") || trimmed.isEmpty()) {
                continue;
            }

            Matcher methodMatcher = JAVA_METHOD.matcher(line);
            if (methodMatcher.find()) {
                hasMembers = true;
                String sig = methodMatcher.group(0).trim();
                // Clean up: remove trailing { or ;
                if (sig.endsWith("{") || sig.endsWith(";")) {
                    sig = sig.substring(0, sig.length() - 1).trim();
                }
                sb.append("  + ").append(sig).append("  :").append(i + 1).append(NEWLINE);
                continue;
            }

            Matcher constrMatcher = JAVA_CONSTRUCTOR.matcher(line);
            if (constrMatcher.find()) {
                hasMembers = true;
                String visibility = constrMatcher.group(1) != null ? constrMatcher.group(1) + " " : "";
                String name = constrMatcher.group(2);
                String params = constrMatcher.group(3);
                sb.append("  + ").append(visibility).append(name).append("(")
                  .append(params.trim()).append(")  :").append(i + 1).append(NEWLINE);
                continue;
            }

            Matcher fieldMatcher = JAVA_FIELD.matcher(line);
            if (fieldMatcher.find()) {
                hasMembers = true;
                String vis = fieldMatcher.group(1) != null ? fieldMatcher.group(1) + " " : "";
                String type = fieldMatcher.group(3);
                String fname = fieldMatcher.group(4);
                sb.append("  - ").append(vis).append(type).append(" ").append(fname).append("  :").append(i + 1).append(NEWLINE);
            }
        }

        if (!hasMembers) {
            sb.append("  (no members found)").append(NEWLINE);
        }

        sb.append(NEWLINE).append("(").append(lines.length).append(" total lines)");
    }

    // ========== Java L2: 关键逻辑分析 ==========

    private void summarizeJavaL2(StringBuilder sb, String content, String[] lines) {
        java.util.List<int[]> methodBounds = findMethodBounds(lines);
        if (methodBounds.isEmpty()) return;

        sb.append(NEWLINE).append(NEWLINE).append("  ── Method Logic Detail ──").append(NEWLINE);

        for (int[] bounds : methodBounds) {
            int startLine = bounds[0];
            int endLine = bounds[1];
            String methodName = bounds.length > 2 ? lines[startLine - 1].trim() : "method";

            if (methodName.length() > 60) methodName = methodName.substring(0, 60) + "...";

            boolean hasDetail = false;
            StringBuilder detailSb = new StringBuilder();

            for (int i = startLine; i < Math.min(endLine, lines.length); i++) {
                String line = lines[i];
                String trimmed = line.trim();

                if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*")
                    || trimmed.startsWith("/*") || trimmed.startsWith("@")) continue;

                String detail = extractKeyLogic(trimmed, i + 1);
                if (detail != null) {
                    detailSb.append(detail);
                    hasDetail = true;
                }
            }

            if (hasDetail) {
                sb.append(NEWLINE).append("  ── ").append(methodName).append(NEWLINE);
                sb.append(detailSb);
            }
        }
    }

    private java.util.List<int[]> findMethodBounds(String[] lines) {
        java.util.List<int[]> bounds = new java.util.ArrayList<>();
        int braceDepth = 0;
        int methodStart = -1;
        int classBraceDepth = -1;
        boolean inMethod = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean hasMethodDecl = JAVA_METHOD.matcher(line).find() || JAVA_CONSTRUCTOR.matcher(line).find();

            if (!inMethod && hasMethodDecl) {
                methodStart = i + 2;
                inMethod = true;
                if (classBraceDepth < 0) {
                    classBraceDepth = countBraceDepth(lines, 0, i);
                }
            }

            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                if (c == '}') braceDepth--;
            }

            if (inMethod && braceDepth <= classBraceDepth) {
                bounds.add(new int[]{methodStart, i, i});
                inMethod = false;
            }
        }

        return bounds;
    }

    private int countBraceDepth(String[] lines, int start, int end) {
        int depth = 0;
        for (int i = start; i <= end && i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                if (c == '{') depth++;
                if (c == '}') depth--;
            }
        }
        return depth;
    }

    private String extractKeyLogic(String trimmed, int lineNum) {
        if (trimmed.startsWith("try ") || trimmed.startsWith("try{") || trimmed.equals("try")) {
            return "  try  :" + lineNum + "\n";
        }
        if (trimmed.startsWith("catch ") || trimmed.startsWith("catch(")) {
            return "  catch  :" + lineNum + "\n";
        }
        if (trimmed.startsWith("if ") || trimmed.startsWith("if(")) {
            String cond = extractCondition(trimmed, 2);
            return "  if (" + cond + ")  :" + lineNum + "\n";
        }
        if (trimmed.startsWith("} else if ") || trimmed.startsWith("}else if")) {
            int ifIdx = trimmed.indexOf("if") + 2;
            String cond = extractCondition(trimmed, ifIdx);
            return "  else if (" + cond + ")  :" + lineNum + "\n";
        }
        if (trimmed.startsWith("} else") || trimmed.equals("}else") || trimmed.startsWith("}else")) {
            return "  else  :" + lineNum + "\n";
        }
        if (trimmed.startsWith("switch ") || trimmed.startsWith("switch(")) {
            return "  switch  :" + lineNum + "\n";
        }
        if (trimmed.startsWith("case ")) {
            String caseVal = trimmed.length() > 30 ? trimmed.substring(0, 30) + "..." : trimmed;
            return "    case " + caseVal.replace("case ", "").replace(":", "") + "  :" + lineNum + "\n";
        }
        if (trimmed.startsWith("for ") || trimmed.startsWith("for(")) {
            String info = trimmed.length() > 50 ? trimmed.substring(0, 50) + "..." : trimmed;
            int oP = info.indexOf('(');
            int cP = info.lastIndexOf(')');
            String cond = (oP >= 0 && cP > oP) ? info.substring(oP + 1, cP) : "";
            return "  for (" + cond + ")  :" + lineNum + "\n";
        }
        if (trimmed.startsWith("while ") || trimmed.startsWith("while(")) {
            return "  while  :" + lineNum + "\n";
        }
        if (trimmed.startsWith("do ") || trimmed.equals("do")) {
            return "  do  :" + lineNum + "\n";
        }
        if (trimmed.startsWith("return ") || trimmed.equals("return;")) {
            String retVal = trimmed.length() > 50 ? trimmed.substring(0, 50) + "..." : trimmed;
            int rs = retVal.indexOf(' ');
            if (rs > 0) retVal = retVal.substring(rs).trim();
            return "  return " + retVal + "  :" + lineNum + "\n";
        }
        if (trimmed.startsWith("throw ")) {
            return "  throw  :" + lineNum + "\n";
        }

        // Method call
        java.util.regex.Pattern mc = java.util.regex.Pattern.compile("(?:\\w+\\.)?(\\w+)\\s*\\([^)]*\\)\\s*;");
        java.util.regex.Matcher m = mc.matcher(trimmed);
        if (m.find() && !trimmed.startsWith("if") && !trimmed.startsWith("for") && !trimmed.startsWith("while")
            && !trimmed.startsWith("switch") && !trimmed.startsWith("return") && !trimmed.startsWith("catch")
            && !trimmed.startsWith("assert") && !trimmed.startsWith("this.") && !trimmed.startsWith("super.")) {
            String call = m.group();
            if (call.length() > 60) call = call.substring(0, 60) + "...";
            return "  . " + call + "  :" + lineNum + "\n";
        }

        return null;
    }

    private String extractCondition(String line, int startIdx) {
        int openParen = line.indexOf('(', startIdx);
        if (openParen < 0) return "";
        int closeParen = findMatchingParen(line, openParen);
        if (closeParen < 0) return "";
        String cond = line.substring(openParen + 1, closeParen).trim();
        if (cond.length() > 50) cond = cond.substring(0, 50) + "...";
        return cond;
    }

    private int findMatchingParen(String s, int openIdx) {
        if (openIdx < 0 || openIdx >= s.length()) return -1;
        int depth = 1;
        for (int i = openIdx + 1; i < s.length(); i++) {
            if (s.charAt(i) == '(') depth++;
            else if (s.charAt(i) == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // ========== JS/TS ==========

    private static final Pattern JS_IMPORT = Pattern.compile(
        "^\\s*(?:import\\s+(?:\\w+|\\{[^}]*\\}|\\*\\s+as\\s+\\w+)\\s+from\\s+['\"]|const\\s+\\w+\\s*=\\s*require\\s*\\()");
    private static final Pattern JS_EXPORT = Pattern.compile(
        "^\\s*(?:export\\s+(?:default\\s+)?)?(?:function\\s+(\\w+)|class\\s+(\\w+)|const\\s+(\\w+)\\s*=|let\\s+(\\w+)\\s*=|var\\s+(\\w+)\\s*=)");
    private static final Pattern JS_FUNCTION = Pattern.compile(
        "(?:async\\s+)?function\\s+(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern JS_ARROW_FN = Pattern.compile(
        "const\\s+(\\w+)\\s*=\\s*(?:async\\s+)?\\(([^)]*)\\)\\s*=>");
    private static final Pattern JS_CLASS = Pattern.compile(
        "class\\s+(\\w+)(?:\\s+extends\\s+(\\w+))?");

    private void summarizeJs(StringBuilder sb, String content, String[] lines, String ext) {
        // Imports
        List<String> imports = new ArrayList<>();
        // Exports/functions/classes
        List<String> exports = new ArrayList<>();
        // Functions
        List<String> functions = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            Matcher im = JS_IMPORT.matcher(trimmed);
            if (im.find()) {
                imports.add(trimmed.substring(0, Math.min(trimmed.length(), 60)).trim());
                continue;
            }

            Matcher em = JS_EXPORT.matcher(trimmed);
            if (em.find()) {
                String name = em.group(1) != null ? em.group(1) :
                              em.group(2) != null ? em.group(2) :
                              em.group(3) != null ? em.group(3) :
                              em.group(4) != null ? em.group(4) : em.group(5);
                String prefix = em.group(0).contains("export") ? "export " : "";
                exports.add(prefix + (em.group(0).contains("function") ? "function" :
                                      em.group(0).contains("class") ? "class" : "const") + " " + name);
                continue;
            }

            Matcher fm = JS_FUNCTION.matcher(trimmed);
            if (fm.find()) {
                functions.add(fm.group(1) + "(" + truncateParams(fm.group(2)) + ")  :" + (i + 1));
                continue;
            }

            Matcher afm = JS_ARROW_FN.matcher(trimmed);
            if (afm.find()) {
                functions.add(afm.group(1) + "(" + truncateParams(afm.group(2)) + ")  :" + (i + 1));
            }
        }

        sb.append("imports: ").append(imports.size()).append(NEWLINE);
        if (imports.size() <= 8) {
            for (String imp : imports) {
                sb.append("  ├── ").append(imp).append(NEWLINE);
            }
        } else {
            for (int i = 0; i < 8; i++) {
                sb.append("  ├── ").append(imports.get(i)).append(NEWLINE);
            }
            sb.append("  └── ... and ").append(imports.size() - 8).append(" more").append(NEWLINE);
        }

        if (!exports.isEmpty()) {
            sb.append(NEWLINE).append("  ── Exports ──").append(NEWLINE);
            for (String exp : exports) {
                sb.append("  + ").append(exp).append(NEWLINE);
            }
        }

        if (!functions.isEmpty()) {
            sb.append(NEWLINE).append("  ── Functions/Methods ──").append(NEWLINE);
            for (String fn : functions) {
                sb.append("  + ").append(fn).append(NEWLINE);
            }
        }

        if (exports.isEmpty() && functions.isEmpty()) {
            // Try to show first few non-comment lines
            sb.append(NEWLINE).append("(top-level content:)").append(NEWLINE);
            int count = 0;
            for (String line : lines) {
                if (!line.trim().isEmpty() && !line.trim().startsWith("//") && !line.trim().startsWith("/*")) {
                    sb.append("  ").append(line.trim().substring(0, Math.min(80, line.trim().length()))).append(NEWLINE);
                    count++;
                    if (count >= 5) break;
                }
            }
        }
    }

    // ========== Vue ==========

    private static final Pattern VUE_TEMPLATE = Pattern.compile("<template>");
    private static final Pattern VUE_SCRIPT = Pattern.compile("<script[^>]*>");
    private static final Pattern VUE_STYLE = Pattern.compile("<style[^>]*>");
    private static final Pattern VUE_COMPONENT_IMPORT = Pattern.compile(
        "import\\s+(\\w+)\\s+from\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern VUE_PROPS = Pattern.compile(
        "(?:props\\s*:|Props\\s*:)\\s*\\{");
    private static final Pattern VUE_COMPUTED = Pattern.compile(
        "(?:computed\\s*:|Computed\\s*:)\\s*\\{");
    private static final Pattern VUE_METHODS = Pattern.compile(
        "(?:methods\\s*:|Methods\\s*:)\\s*\\{");

    private void summarizeVue(StringBuilder sb, String content, String[] lines) {
        boolean hasTemplate = false, hasScript = false, hasStyle = false;
        List<String> components = new ArrayList<>();
        List<String> dataProps = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (VUE_TEMPLATE.matcher(trimmed).find()) hasTemplate = true;
            if (VUE_SCRIPT.matcher(trimmed).find()) hasScript = true;
            if (VUE_STYLE.matcher(trimmed).find()) {
                hasStyle = true;
                if (trimmed.contains("scoped")) {
                    hasStyle = true;
                }
            }

            Matcher cm = VUE_COMPONENT_IMPORT.matcher(trimmed);
            if (cm.find()) {
                components.add(cm.group(1) + " from " + cm.group(2));
            }
        }

        sb.append("Sections: ");
        if (hasTemplate) sb.append("<template> ");
        if (hasScript) sb.append("<script> ");
        if (hasStyle) sb.append("<style> ");
        sb.append(NEWLINE);

        if (!components.isEmpty()) {
            sb.append(NEWLINE).append("  ── Components ──").append(NEWLINE);
            for (String comp : components) {
                sb.append("  + ").append(comp).append(NEWLINE);
            }
        }

        // Show key data/props/computed/methods markers
        String scriptSection = extractSectionAfter(lines, "<script");
        if (scriptSection != null) {
            String[] scriptLines = scriptSection.split("\n");
            for (String line : scriptLines) {
                String trimmed = line.trim();
                if (VUE_PROPS.matcher(trimmed).find()) {
                    sb.append(NEWLINE).append("  ── Props defined ──").append(NEWLINE);
                    // Extract prop names in the following lines
                    extractObjectKeys(scriptLines, java.util.Arrays.asList(scriptLines).indexOf(line) + 1, sb, "props");
                } else if (VUE_METHODS.matcher(trimmed).find()) {
                    sb.append(NEWLINE).append("  ── Methods ──").append(NEWLINE);
                    extractFunctionKeys(scriptLines, java.util.Arrays.asList(scriptLines).indexOf(line) + 1, sb);
                } else if (VUE_COMPUTED.matcher(trimmed).find()) {
                    sb.append(NEWLINE).append("  ── Computed ──").append(NEWLINE);
                    extractFunctionKeys(scriptLines, java.util.Arrays.asList(scriptLines).indexOf(line) + 1, sb);
                }
            }
        }

        // Show template structure (first level)
        if (hasTemplate) {
            String templateSection = extractSection(lines, "<template>", "</template>");
            if (templateSection != null) {
                sb.append(NEWLINE).append("  ── Template ──").append(NEWLINE);
                String[] tLines = templateSection.split("\n");
                int shown = 0;
                for (String tl : tLines) {
                    String t = tl.trim();
                    if (t.startsWith("<") && !t.startsWith("<!--")) {
                        sb.append("  ").append(t.substring(0, Math.min(80, t.length()))).append(NEWLINE);
                        shown++;
                        if (shown >= 8) break;
                    }
                }
            }
        }
    }

    // ========== JSP ==========

    private static final Pattern JSP_DIRECTIVE = Pattern.compile(
        "<%@\\s*(page|include|taglib)\\s+([^%]*)%>");
    private static final Pattern JSP_ACTION = Pattern.compile(
        "<jsp:([\\w]+)\\s+[^>]*>");

    private void summarizeJsp(StringBuilder sb, String content, String[] lines) {
        sb.append("Type: JSP").append(NEWLINE).append(NEWLINE);

        List<String> directives = new ArrayList<>();
        List<String> taglibs = new ArrayList<>();
        boolean hasScriptlet = false;
        boolean hasExpression = false;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            Matcher dm = JSP_DIRECTIVE.matcher(trimmed);
            if (dm.find()) {
                String type = dm.group(1);
                String attrs = dm.group(2).trim();
                if ("taglib".equals(type)) {
                    taglibs.add(attrs);
                } else {
                    directives.add(type + " " + attrs);
                }
            }
            if (trimmed.contains("<%!") || trimmed.contains("<%=")) hasExpression = true;
            if (trimmed.contains("<%") && !trimmed.contains("<%@")) hasScriptlet = true;
        }

        if (!taglibs.isEmpty()) {
            sb.append("  ── Taglibs ──").append(NEWLINE);
            for (String t : taglibs) sb.append("  + ").append(t).append(NEWLINE);
        }
        if (!directives.isEmpty()) {
            sb.append(NEWLINE).append("  ── Directives ──").append(NEWLINE);
            for (String d : directives) sb.append("  + ").append(d).append(NEWLINE);
        }
        if (hasExpression) sb.append(NEWLINE).append("  has: EL expressions (<%=)").append(NEWLINE);
        if (hasScriptlet) sb.append("  has: Scriptlets (<% ... %>)").append(NEWLINE);

        // Show form/table/div structure
        sb.append(NEWLINE).append("  ── HTML Structure ──").append(NEWLINE);
        int count = 0;
        for (String line : lines) {
            String t = line.trim();
            if ((t.startsWith("<form") || t.startsWith("<table") || t.startsWith("<div")
                || t.startsWith("<input") || t.startsWith("<select") || t.startsWith("<c:"))
                && !t.startsWith("<!--")) {
                sb.append("  ").append(t.substring(0, Math.min(80, t.length()))).append(NEWLINE);
                count++;
                if (count >= 10) break;
            }
        }
    }

    // ========== HTML ==========

    private void summarizeHtml(StringBuilder sb, String content, String[] lines) {
        List<String> metaTags = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        List<String> forms = new ArrayList<>();
        List<String> ids = new ArrayList<>();

        for (String line : lines) {
            String t = line.trim();
            if (t.contains("<meta ")) metaTags.add(t);
            if (t.contains("<script")) scripts.add(t);
            if (t.contains("<form ")) {
                Matcher m = Pattern.compile("<form\\s+[^>]*id=['\"]([^'\"]+)['\"]").matcher(t);
                if (m.find()) forms.add("form#" + m.group(1));
                else forms.add("<form>");
            }
            Matcher idMatcher = Pattern.compile("id=['\"]([^'\"]+)['\"]").matcher(t);
            while (idMatcher.find()) {
                ids.add(idMatcher.group(1));
            }
        }

        sb.append("Type: HTML").append(NEWLINE);
        sb.append("scripts: ").append(scripts.size()).append(NEWLINE);
        if (!forms.isEmpty()) {
            sb.append(NEWLINE).append("  ── Forms ──").append(NEWLINE);
            for (String f : forms) sb.append("  + ").append(f).append(NEWLINE);
        }
        if (!ids.isEmpty()) {
            sb.append(NEWLINE).append("  ── Element IDs ──").append(NEWLINE);
            int shown = 0;
            for (String id : ids) {
                sb.append("  - ").append(id).append(NEWLINE);
                shown++;
                if (shown >= 10) { sb.append("  ... and ").append(ids.size() - 10).append(" more").append(NEWLINE); break; }
            }
        }
    }

    // ========== XML ==========

    private void summarizeXml(StringBuilder sb, String content, String[] lines) {
        Pattern rootPattern = Pattern.compile("<(\\w+)(?:[^>]*>|\\s*/?>)");
        Pattern nsPattern = Pattern.compile("xmlns:?(\\w*)=\"([^\"]+)\"");

        String root = null;
        List<String> namespaces = new ArrayList<>();
        List<String> topLevelChildren = new ArrayList<>();
        int depth = 0;
        boolean rootFound = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("<?xml") || trimmed.startsWith("<!--") || trimmed.isEmpty()) continue;

            if (!rootFound) {
                Matcher rm = rootPattern.matcher(trimmed);
                if (rm.find()) {
                    root = rm.group(1);
                    rootFound = true;
                    Matcher nm = nsPattern.matcher(trimmed);
                    while (nm.find()) {
                        String prefix = nm.group(1).isEmpty() ? "(default)" : nm.group(1);
                        namespaces.add(prefix + "=" + nm.group(2));
                    }
                }
            } else if (depth == 1 && trimmed.startsWith("<") && !trimmed.startsWith("</")) {
                Matcher cm = rootPattern.matcher(trimmed);
                if (cm.find()) {
                    topLevelChildren.add(cm.group(1));
                }
            }

            // Track depth
            for (char c : trimmed.toCharArray()) {
                if (c == '<') depth++;
                else if (c == '>') depth--;
            }
        }

        if (root != null) {
            sb.append("Root: <").append(root).append(">").append(NEWLINE);
        }
        if (!namespaces.isEmpty()) {
            sb.append(NEWLINE).append("  ── Namespaces ──").append(NEWLINE);
            for (String ns : namespaces) sb.append("  + ").append(ns).append(NEWLINE);
        }
        if (!topLevelChildren.isEmpty()) {
            sb.append(NEWLINE).append("  ── Top-level Elements ──").append(NEWLINE);
            for (String child : topLevelChildren) {
                sb.append("  + <").append(child).append(">").append(NEWLINE);
            }
        }
    }

    // ========== YAML ==========

    private void summarizeYaml(StringBuilder sb, String content, String[] lines) {
        Map<String, Integer> topKeys = new LinkedHashMap<>();
        String currentKey = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;

            // Top-level key (no leading spaces, ends with :)
            Matcher m = Pattern.compile("^(\\w[\\w/-]*)\\s*:").matcher(line);
            if (m.find()) {
                currentKey = m.group(1);
                topKeys.merge(currentKey, 1, Integer::sum);
            }
        }

        sb.append("Type: YAML").append(NEWLINE);
        if (!topKeys.isEmpty()) {
            sb.append(NEWLINE).append("  ── Top-level Keys ──").append(NEWLINE);
            for (Map.Entry<String, Integer> e : topKeys.entrySet()) {
                sb.append("  + ").append(e.getKey()).append(NEWLINE);
            }
        }
    }

    // ========== Properties ==========

    private void summarizeProperties(StringBuilder sb, String content, String[] lines) {
        int count = 0;
        List<String> keys = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#") && !t.startsWith("!")) {
                count++;
                String key = t.contains("=") ? t.substring(0, t.indexOf('=')).trim() :
                             t.contains(":") ? t.substring(0, t.indexOf(':')).trim() : t;
                keys.add(key);
            }
        }

        sb.append("Properties: ").append(count).append(NEWLINE);
        if (!keys.isEmpty()) {
            sb.append(NEWLINE).append("  ── Keys ──").append(NEWLINE);
            int shown = 0;
            for (String k : keys) {
                sb.append("  + ").append(k).append(NEWLINE);
                shown++;
                if (shown >= 20) { sb.append("  ... and ").append(keys.size() - 20).append(" more").append(NEWLINE); break; }
            }
        }
    }

    // ========== CSS ==========

    private void summarizeCss(StringBuilder sb, String content, String[] lines) {
        Pattern selectorPattern = Pattern.compile("^\\s*([.#]?[\\w-]+(?:\\s*[+>~]\\s*[.#]?[\\w-]+)*)\\s*\\{");
        List<String> selectors = new ArrayList<>();

        for (String line : lines) {
            Matcher m = selectorPattern.matcher(line);
            if (m.find()) {
                selectors.add(m.group(1).trim());
            }
        }

        sb.append("Selectors: ").append(selectors.size()).append(NEWLINE);
        if (!selectors.isEmpty()) {
            sb.append(NEWLINE).append("  ── Selectors ──").append(NEWLINE);
            int shown = 0;
            for (String s : selectors) {
                sb.append("  + ").append(s).append(NEWLINE);
                shown++;
                if (shown >= 15) { sb.append("  ... and ").append(selectors.size() - 15).append(" more").append(NEWLINE); break; }
            }
        }
    }

    // ========== JSON ==========

    private void summarizeJson(StringBuilder sb, String content, String[] lines) {
        Pattern topKeyPattern = Pattern.compile("\"([^\"]+)\"\\s*:");
        Set<String> topKeys = new LinkedHashSet<>();

        boolean inTopLevel = true;
        int braceDepth = 0;
        for (String line : lines) {
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }
            if (braceDepth == 1 && inTopLevel) {
                Matcher m = topKeyPattern.matcher(line);
                if (m.find()) topKeys.add(m.group(1));
            }
            if (braceDepth > 1) inTopLevel = false;
        }

        sb.append("Type: JSON, ").append(lines.length).append(" lines").append(NEWLINE);
        if (!topKeys.isEmpty()) {
            sb.append(NEWLINE).append("  ── Top-level Keys ──").append(NEWLINE);
            for (String k : topKeys) sb.append("  + ").append(k).append(NEWLINE);
        }
    }

    // ========== Generic ==========

    private void summarizeGeneric(StringBuilder sb, String content, String[] lines) {
        int nonEmpty = 0;
        for (String l : lines) {
            if (!l.trim().isEmpty()) nonEmpty++;
        }
        sb.append("Lines: ").append(lines.length).append(", non-empty: ").append(nonEmpty).append(NEWLINE);
        sb.append(NEWLINE).append("  ── First 15 lines ──").append(NEWLINE);
        int shown = 0;
        for (String line : lines) {
            if (shown >= 15) break;
            if (!line.trim().isEmpty()) {
                String display = line.trim();
                sb.append("  ").append(display.substring(0, Math.min(80, display.length()))).append(NEWLINE);
                shown++;
            }
        }
    }

    // ========== Utility methods ==========

    private String appendAnnotation(String[] lines, int idx) {
        StringBuilder sb = new StringBuilder();
        for (int i = idx - 1; i >= 0 && i >= idx - 5; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("@")) {
                sb.insert(0, "  " + trimmed + NEWLINE);
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*")) {
                break;
            }
        }
        return sb.toString();
    }

    private String truncateParams(String params) {
        if (params == null || params.trim().isEmpty()) return "";
        String[] parts = params.split(",");
        if (parts.length <= 3) return params.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts[i].trim());
        }
        sb.append(", ...");
        return sb.toString();
    }

    private String extractSectionAfter(String[] lines, String marker) {
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        for (String line : lines) {
            if (found) {
                sb.append(line).append("\n");
            } else if (line.contains(marker)) {
                found = true;
            }
        }
        return found ? sb.toString() : null;
    }

    private String extractSection(String[] lines, String startMarker, String endMarker) {
        StringBuilder sb = new StringBuilder();
        boolean inSection = false;
        for (String line : lines) {
            if (line.contains(startMarker)) {
                inSection = true;
                continue;
            }
            if (inSection && line.contains(endMarker)) break;
            if (inSection) sb.append(line).append("\n");
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private void extractObjectKeys(String[] lines, int startIdx, StringBuilder sb, String context) {
        int depth = 0;
        int shown = 0;
        for (int i = startIdx; i < lines.length; i++) {
            String t = lines[i].trim();
            for (char c : t.toCharArray()) {
                if (c == '{') depth++;
                if (c == '}') depth--;
            }
            if (depth <= 0) break;
            Matcher m = Pattern.compile("(\\w+)\\s*:").matcher(t);
            if (m.find() && !t.trim().startsWith("}")) {
                sb.append("  - ").append(m.group(1)).append(NEWLINE);
                shown++;
                if (shown >= 10) { sb.append("  ... and more").append(NEWLINE); break; }
            }
        }
    }

    private void extractFunctionKeys(String[] lines, int startIdx, StringBuilder sb) {
        int depth = 0;
        int shown = 0;
        for (int i = startIdx; i < lines.length; i++) {
            String t = lines[i].trim();
            for (char c : t.toCharArray()) {
                if (c == '{') depth++;
                if (c == '}') depth--;
            }
            if (depth < 0) break;
            Matcher m = Pattern.compile("(\\w+)\\s*\\(([^)]*)\\)\\s*\\{?").matcher(t);
            if (m.find() && depth >= 0) {
                sb.append("  + ").append(m.group(1)).append("(")
                  .append(truncateParams(m.group(2))).append(")").append(NEWLINE);
                shown++;
                if (shown >= 15) { sb.append("  ... and more").append(NEWLINE); break; }
            }
        }
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private static final String NEWLINE = System.lineSeparator();
}
