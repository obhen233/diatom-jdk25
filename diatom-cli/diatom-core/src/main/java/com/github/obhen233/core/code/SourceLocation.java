package com.github.obhen233.core.code;

/**
 * 源代码定位结果
 */
public class SourceLocation {
    private String filePath;
    private int startLine;
    private int endLine;
    private int startColumn;
    private int endColumn;
    private String className;
    private String methodName;
    private CodeStyle style;

    public SourceLocation() {}

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public int getStartLine() {
        return startLine;
    }

    public void setStartLine(int startLine) {
        this.startLine = startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public void setEndLine(int endLine) {
        this.endLine = endLine;
    }

    public int getStartColumn() {
        return startColumn;
    }

    public void setStartColumn(int startColumn) {
        this.startColumn = startColumn;
    }

    public int getEndColumn() {
        return endColumn;
    }

    public void setEndColumn(int endColumn) {
        this.endColumn = endColumn;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public CodeStyle getStyle() {
        return style;
    }

    public void setStyle(CodeStyle style) {
        this.style = style;
    }

    @Override
    public String toString() {
        return "SourceLocation{" +
                "filePath='" + filePath + '\'' +
                ", startLine=" + startLine +
                ", endLine=" + endLine +
                ", className='" + className + '\'' +
                ", methodName='" + methodName + '\'' +
                '}';
    }
}
