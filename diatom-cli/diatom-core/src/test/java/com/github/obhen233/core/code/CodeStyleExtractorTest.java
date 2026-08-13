package com.github.obhen233.core.code;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * CodeStyleExtractor 测试
 */
public class CodeStyleExtractorTest {

    private final CodeStyleExtractor extractor = new CodeStyleExtractor();

    @Test
    public void testDetectLineSeparator_CRLF() {
        String content = "line1\r\nline2\r\nline3";
        assertEquals("\r\n", extractor.detectLineSeparator(content));
    }

    @Test
    public void testDetectLineSeparator_LF() {
        String content = "line1\nline2\nline3";
        assertEquals("\n", extractor.detectLineSeparator(content));
    }

    @Test
    public void testDetectLineSeparator_CR() {
        String content = "line1\rline2\rline3";
        assertEquals("\r", extractor.detectLineSeparator(content));
    }

    @Test
    public void testDetectIndent_Tab() {
        String line = "\t\tpublic void test()";
        assertEquals("\t", extractor.detectIndent(line));
    }

    @Test
    public void testDetectIndent_4Spaces() {
        String line = "    public void test()";
        assertEquals("    ", extractor.detectIndent(line));
    }

    @Test
    public void testDetectIndent_2Spaces() {
        String line = "  public void test()";
        assertEquals("  ", extractor.detectIndent(line));
    }

    @Test
    public void testDetectIndent_EmptyLine() {
        String line = "";
        assertEquals("    ", extractor.detectIndent(line));
    }

    @Test
    public void testDetectBraceStyle_KR() {
        String[] lines = {
            "public class Test {",
            "    public void method() {",
            "    }",
            "}"
        };
        assertEquals(CodeStyle.BraceStyle.K_R, extractor.detectBraceStyle(lines, 1));
    }

    @Test
    public void testDetectBraceStyle_Allman() {
        String[] lines = {
            "public class Test",
            "{",
            "    public void method()",
            "    {",
            "    }",
            "}"
        };
        assertEquals(CodeStyle.BraceStyle.ALLMAN, extractor.detectBraceStyle(lines, 1));
    }

    @Test
    public void testExtractPackageName() {
        String source = "package com.github.obhen233.core.code;\n" +
                        "public class Test {\n" +
                        "}";
        assertEquals("com.github.obhen233.core.code", extractor.extractPackageName(source));
    }

    @Test
    public void testExtractPackageName_Empty() {
        String source = "public class Test {}";
        assertEquals("", extractor.extractPackageName(source));
    }

    @Test
    public void testExtract() {
        String source = "package com.example;\r\n" +
                        "\r\n" +
                        "public class Test {\r\n" +
                        "    private int value;\r\n" +
                        "    \r\n" +
                        "    public void method() {\r\n" +
                        "    }\r\n" +
                        "}";

        CodeStyle style = extractor.extract(source, 3);

        assertEquals("\r\n", style.getLineSeparator());
        assertEquals("    ", style.getIndent());
        assertEquals(CodeStyle.BraceStyle.K_R, style.getBraceStyle());
    }
}
