package com.github.obhen233.core.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 修改应用器
 */
public class ModificationApplicator {
    private static final Logger logger = LoggerFactory.getLogger(ModificationApplicator.class);

    private final SemanticLocator locator;
    private final CodeStyleExtractor styleExtractor;

    public ModificationApplicator() {
        this.locator = new SemanticLocator();
        this.styleExtractor = new CodeStyleExtractor();
    }

    /**
     * 应用修改指令到源码
     */
    public String apply(String sourceCode, ModificationInstruction instruction) {
        SourceLocation location = null;

        // 根据目标类型进行语义定位
        switch (instruction.getType()) {
            case REPLACE_METHOD_BODY:
                location = locateMethod(instruction);
                if (location == null) {
                    throw new CodeModificationException("Cannot locate method: " + instruction.getTarget());
                }
                return replaceMethodBody(sourceCode, location, instruction);

            case ADD_METHOD:
                location = locateClass(instruction);
                if (location == null) {
                    throw new CodeModificationException("Cannot locate class: " + instruction.getTarget());
                }
                return addMethod(sourceCode, location, instruction);

            case ADD_FIELD:
                location = locateClass(instruction);
                if (location == null) {
                    throw new CodeModificationException("Cannot locate class: " + instruction.getTarget());
                }
                return addField(sourceCode, location, instruction);

            case ADD_IMPORT:
                return addImport(sourceCode, instruction);

            case MODIFY_CLASS_BODY:
                location = locateClass(instruction);
                if (location == null) {
                    throw new CodeModificationException("Cannot locate class: " + instruction.getTarget());
                }
                return modifyClassBody(sourceCode, location, instruction);

            case INSERT_STATEMENT:
                location = locateMethod(instruction);
                if (location == null) {
                    throw new CodeModificationException("Cannot locate method: " + instruction.getTarget());
                }
                return insertStatement(sourceCode, location, instruction);

            case REPLACE_FILE:
                return replaceFile(instruction);

            default:
                throw new UnsupportedOperationException("Unsupported modification type: " + instruction.getType());
        }
    }

    private SourceLocation locateMethod(ModificationInstruction instruction) {
        String target = instruction.getTarget();
        String className = extractClassName(target);
        String methodName = extractMethodName(target);
        String filePath = instruction.getFilePath();

        // 从context中获取源码
        String source = instruction.getContext().get("source");
        if (source == null) {
            throw new CodeModificationException("Source code not provided in context");
        }

        return locator.locateMethod(filePath, className, methodName, source);
    }

    private SourceLocation locateClass(ModificationInstruction instruction) {
        String target = instruction.getTarget();
        String filePath = instruction.getFilePath();

        String source = instruction.getContext().get("source");
        if (source == null) {
            throw new CodeModificationException("Source code not provided in context");
        }

        return locator.locateClass(filePath, target, source);
    }

    /**
     * 替换方法体
     */
    private String replaceMethodBody(String source, SourceLocation location, ModificationInstruction instruction) {
        String newCode = instruction.getNewCode();
        CodeStyle style = location.getStyle();

        String lineSep = style.getLineSeparator();
        String indent = style.getIndent();
        String doubleIndent = style.getDoubleIndent();

        // 解析新代码（支持 \n 转义）
        String[] newLines = parseCodeLines(newCode, lineSep);

        // 找到 { 所在行和 } 所在行
        String[] sourceLines = source.split("(?<=" + Pattern.quote(lineSep) + ")|(?=" + Pattern.quote(lineSep) + ")");
        if (sourceLines.length == 0) {
            sourceLines = new String[]{source};
        }

        // SemanticLocator returns 1-indexed line numbers, convert to 0-indexed for array access
        int startLine = location.getStartLine() - 1;
        int endLine = location.getEndLine() - 1;

        int braceOpenLine = -1;
        int braceCloseLine = -1;

        for (int i = startLine; i < Math.min(endLine + 1, sourceLines.length); i++) {
            if (sourceLines[i].contains("{")) {
                braceOpenLine = i;
                break;
            }
        }

        for (int i = Math.max(endLine, braceOpenLine); i >= Math.max(braceOpenLine, 0); i--) {
            if (sourceLines[i].contains("}")) {
                braceCloseLine = i;
                break;
            }
        }

        if (braceOpenLine < 0 || braceCloseLine < 0 || braceOpenLine >= braceCloseLine) {
            throw new CodeModificationException("Cannot locate method body braces");
        }

        // 构建新方法体
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < sourceLines.length; i++) {
            if (i < braceOpenLine) {
                result.append(sourceLines[i]);
            } else if (i == braceOpenLine) {
                // 保留 { 及其前面的内容（可能包含 throws 声明）
                result.append(sourceLines[i]);
                result.append(lineSep);

                // 添加新方法体
                for (String newLine : newLines) {
                    result.append(doubleIndent).append(newLine.trim());
                    result.append(lineSep);
                }
            } else if (i > braceOpenLine && i < braceCloseLine) {
                // 跳过旧方法体
                continue;
            } else if (i == braceCloseLine) {
                // 添加结束 }
                result.append(indent).append("}");
                if (i < sourceLines.length - 1) {
                    result.append(lineSep);
                }
            } else {
                result.append(sourceLines[i]);
            }
        }

        return result.toString();
    }

    /**
     * 添加方法
     */
    private String addMethod(String source, SourceLocation location, ModificationInstruction instruction) {
        String newCode = instruction.getNewCode();
        CodeStyle style = location.getStyle();
        String lineSep = style.getLineSeparator();
        String indent = style.getIndent();

        String[] newLines = parseCodeLines(newCode, lineSep);

        StringBuilder result = new StringBuilder();
        String[] sourceLines = source.split("(?<=" + Pattern.quote(lineSep) + ")|(?=" + Pattern.quote(lineSep) + ")");
        if (sourceLines.length == 0) {
            sourceLines = new String[]{source};
        }

        // 在类结束 } 前插入新方法
        int insertLine = location.getEndLine();
        if (insertLine > 0 && insertLine <= sourceLines.length) {
            insertLine--;  // 插入在 } 之前
        }

        for (int i = 0; i < sourceLines.length; i++) {
            if (i == insertLine) {
                // 添加新方法
                for (String newLine : newLines) {
                    result.append(indent).append(newLine.trim());
                    result.append(lineSep);
                }
                result.append(lineSep);
            }
            result.append(sourceLines[i]);
        }

        return result.toString();
    }

    /**
     * 添加字段
     */
    private String addField(String source, SourceLocation location, ModificationInstruction instruction) {
        String newCode = instruction.getNewCode();
        CodeStyle style = location.getStyle();
        String lineSep = style.getLineSeparator();
        String indent = style.getIndent();

        String[] newLines = parseCodeLines(newCode, lineSep);

        StringBuilder result = new StringBuilder();
        String[] sourceLines = source.split("(?<=" + Pattern.quote(lineSep) + ")|(?=" + Pattern.quote(lineSep) + ")");
        if (sourceLines.length == 0) {
            sourceLines = new String[]{source};
        }

        // 在类的第一个方法之前插入字段
        int insertLine = findFirstMethodLine(source, location.getStartLine());

        for (int i = 0; i < sourceLines.length; i++) {
            if (i == insertLine && insertLine > 0) {
                // 添加新字段
                for (String newLine : newLines) {
                    result.append(indent).append(newLine.trim());
                    result.append(lineSep);
                }
                result.append(lineSep);
            }
            result.append(sourceLines[i]);
        }

        return result.toString();
    }

    /**
     * 添加import
     */
    private String addImport(String source, ModificationInstruction instruction) {
        String newImport = instruction.getNewCode().trim();
        if (!newImport.startsWith("import ")) {
            newImport = "import " + newImport;
        }
        if (!newImport.endsWith(";")) {
            newImport += ";";
        }

        String lineSep = styleExtractor.detectLineSeparator(source);
        int insertLine = locator.findImportInsertLine(source);

        String[] sourceLines = source.split("(?<=" + Pattern.quote(lineSep) + ")|(?=" + Pattern.quote(lineSep) + ")");
        if (sourceLines.length == 0) {
            sourceLines = new String[]{source};
        }

        StringBuilder result = new StringBuilder();

        for (int i = 0; i < sourceLines.length; i++) {
            if (i == insertLine) {
                result.append(newImport).append(lineSep);
            }
            result.append(sourceLines[i]);
        }

        return result.toString();
    }

    /**
     * 修改类内部
     */
    private String modifyClassBody(String source, SourceLocation location, ModificationInstruction instruction) {
        // 委托给 replaceMethodBody 或其他方法
        String target = instruction.getTarget();
        if (target.contains(".")) {
            return replaceMethodBody(source,
                    locator.locateMethod(instruction.getFilePath(),
                            extractClassName(target),
                            extractMethodName(target),
                            instruction.getContext().get("source")),
                    instruction);
        }
        throw new CodeModificationException("Invalid target for MODIFY_CLASS_BODY: " + target);
    }

    /**
     * 插入语句
     */
    private String insertStatement(String source, SourceLocation location, ModificationInstruction instruction) {
        // 在方法体的特定位置插入语句
        // 目前简单实现在方法开头插入
        return replaceMethodBody(source, location, instruction);
    }

    /**
     * 替换整个文件
     */
    private String replaceFile(ModificationInstruction instruction) {
        return instruction.getNewCode();
    }

    /**
     * 解析代码行
     */
    private String[] parseCodeLines(String code, String lineSep) {
        if (code == null) return new String[0];

        // 处理 \n 转义
        String normalized = code.replace("\\n", "\n").replace("\\r", "\r");
        return normalized.split("\n|\r\n|\r");
    }

    /**
     * 找到类的第一个方法行
     */
    private int findFirstMethodLine(String source, int classStartLine) {
        String[] lines = source.split("\n|\r\n|\r");

        Pattern methodPattern = Pattern.compile(
                "(?:public|protected|private)\\s+(?:static\\s+)?[\\w<>,\\s]+\\s+\\w+\\s*\\(");

        for (int i = classStartLine; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("class ") || line.startsWith("interface ") || line.startsWith("enum ")) {
                continue;
            }
            if (line.startsWith("}")) {
                break;
            }
            if (methodPattern.matcher(line).find()) {
                return i;
            }
        }

        return classStartLine + 1;
    }

    /**
     * 从目标字符串中提取类名
     */
    private String extractClassName(String target) {
        if (target.contains(".")) {
            int lastDot = target.lastIndexOf('.');
            return target.substring(0, lastDot);
        }
        return target;
    }

    /**
     * 从目标字符串中提取方法名
     */
    private String extractMethodName(String target) {
        if (target.contains(".")) {
            int lastDot = target.lastIndexOf('.');
            return target.substring(lastDot + 1);
        }
        return target;
    }
}
