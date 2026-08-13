package com.github.obhen233.core.code;

/**
 * 代码格式风格
 */
public class CodeStyle {
    private String indent = "    ";           // 默认4空格
    private String lineSeparator = "\n";     // 默认Unix风格
    private BraceStyle braceStyle = BraceStyle.K_R;

    public enum BraceStyle {
        K_R,       // if () { - K&R风格
        ALLMAN     // if ()\n{ - Allman风格
    }

    public CodeStyle() {}

    public CodeStyle(String indent, String lineSeparator, BraceStyle braceStyle) {
        this.indent = indent;
        this.lineSeparator = lineSeparator;
        this.braceStyle = braceStyle;
    }

    public String getIndent() {
        return indent;
    }

    public void setIndent(String indent) {
        this.indent = indent;
    }

    public String getLineSeparator() {
        return lineSeparator;
    }

    public void setLineSeparator(String lineSeparator) {
        this.lineSeparator = lineSeparator;
    }

    public BraceStyle getBraceStyle() {
        return braceStyle;
    }

    public void setBraceStyle(BraceStyle braceStyle) {
        this.braceStyle = braceStyle;
    }

    /**
     * 获取两倍缩进
     */
    public String getDoubleIndent() {
        return indent + indent;
    }

    @Override
    public String toString() {
        return "CodeStyle{" +
                "indent='" + indent.replace("\t", "\\t") + '\'' +
                ", lineSeparator=" + (lineSeparator.equals("\r\n") ? "\\r\\n" : "\\n") +
                ", braceStyle=" + braceStyle +
                '}';
    }
}
