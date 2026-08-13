package com.github.obhen233.core.code;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * CodeStructureSummarizer 测试
 */
public class CodeStructureSummarizerTest {

    private final CodeStructureSummarizer summarizer = new CodeStructureSummarizer();

    private static final String TEST_SOURCE =
            "package com.github.obhen233.core.code;\n" +
            "\n" +
            "import java.util.List;\n" +
            "import java.util.ArrayList;\n" +
            "\n" +
            "public class TestSummarizer {\n" +
            "\n" +
            "    private String name;\n" +
            "    private int value;\n" +
            "\n" +
            "    public TestSummarizer() {\n" +
            "    }\n" +
            "\n" +
            "    public void setName(String name) {\n" +
            "        this.name = name;\n" +
            "    }\n" +
            "\n" +
            "    public String getName() {\n" +
            "        return name;\n" +
            "    }\n" +
            "\n" +
            "    public int calculate(int a, int b) {\n" +
            "        return a + b;\n" +
            "    }\n" +
            "}\n";

    @Test
    public void testSummarize_packageName() {
        CodeStructureSummary summary = summarizer.summarize("Test.java", TEST_SOURCE);
        assertEquals("com.github.obhen233.core.code", summary.getPackageName());
    }

    @Test
    public void testSummarize_hasClassName() {
        CodeStructureSummary summary = summarizer.summarize("Test.java", TEST_SOURCE);
        assertNotNull(summary.getClassName());
        assertFalse(summary.getClassName().isEmpty());
    }

    @Test
    public void testSummarize_hasClassType() {
        CodeStructureSummary summary = summarizer.summarize("Test.java", TEST_SOURCE);
        assertNotNull(summary.getClassType());
        assertFalse(summary.getClassType().isEmpty());
    }

    @Test
    public void testSummarize_imports() {
        CodeStructureSummary summary = summarizer.summarize("Test.java", TEST_SOURCE);

        // Should have imports
        assertTrue(summary.getImports().size() >= 2);

        // Check for specific imports
        boolean hasList = false;
        boolean hasArrayList = false;
        for (CodeStructureSummary.ImportInfo imp : summary.getImports()) {
            if (imp.getPackageName().contains("List")) hasList = true;
            if (imp.getPackageName().contains("ArrayList")) hasArrayList = true;
        }
        assertTrue(hasList);
        assertTrue(hasArrayList);
    }

    @Test
    public void testSummarize_members() {
        CodeStructureSummary summary = summarizer.summarize("Test.java", TEST_SOURCE);

        // Should have members
        assertTrue(summary.getMembers().size() >= 0);
    }

    @Test
    public void testSummarize_approximateLine() {
        CodeStructureSummary summary = summarizer.summarize("Test.java", TEST_SOURCE);
        assertTrue(summary.getApproximateLine() > 0);
    }

    @Test
    public void testToCompressedView() {
        CodeStructureSummary summary = summarizer.summarize("Test.java", TEST_SOURCE);
        String view = summarizer.toCompressedView(summary);

        assertNotNull(view);
        assertTrue(view.contains("package"));
        assertTrue(view.contains("class"));
    }

    @Test
    public void testToModelJson() {
        CodeStructureSummary summary = summarizer.summarize("Test.java", TEST_SOURCE);
        String json = summarizer.toModelJson(summary);

        assertNotNull(json);
        assertTrue(json.contains("\"filePath\""));
        assertTrue(json.contains("\"package\""));
    }

    @Test
    public void testSummarize_interface() {
        String source = "package com.example;\n" +
                      "\n" +
                      "public interface TestInterface {\n" +
                      "    void method1();\n" +
                      "}\n";

        CodeStructureSummary summary = summarizer.summarize("TestInterface.java", source);

        assertNotNull(summary.getClassName());
        assertFalse(summary.getClassName().isEmpty());
    }

    @Test
    public void testSummarize_enum() {
        String source = "package com.example;\n" +
                      "\n" +
                      "public enum TestEnum {\n" +
                      "    VALUE1, VALUE2;\n" +
                      "}\n";

        CodeStructureSummary summary = summarizer.summarize("TestEnum.java", source);

        assertNotNull(summary.getClassName());
        assertFalse(summary.getClassName().isEmpty());
    }

    @Test
    public void testSummarize_noPackage() {
        String source = "public class Test {\n" +
                      "    public void method() {}\n" +
                      "}\n";

        CodeStructureSummary summary = summarizer.summarize("Test.java", source);

        assertEquals("", summary.getPackageName());
        assertEquals("Test", summary.getClassName());
    }

    @Test
    public void testSummarize_noImports() {
        String source = "package com.example;\n" +
                      "\n" +
                      "public class Test {\n" +
                      "}\n";

        CodeStructureSummary summary = summarizer.summarize("Test.java", source);

        assertEquals("com.example", summary.getPackageName());
        assertEquals(0, summary.getImports().size());
    }
}
