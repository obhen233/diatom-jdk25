package com.github.obhen233.core.code;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * SemanticLocator 测试
 */
public class SemanticLocatorTest {

    private final SemanticLocator locator = new SemanticLocator();

    private static final String TEST_SOURCE =
            "package com.github.obhen233.core.code;\n" +
            "\n" +
            "import java.util.List;\n" +
            "import java.io.IOException;\n" +
            "\n" +
            "/**\n" +
            " * Test class\n" +
            " */\n" +
            "public class TestClass {\n" +
            "\n" +
            "    private String name;\n" +
            "    private int value;\n" +
            "\n" +
            "    public TestClass() {\n" +
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
            "\n" +
            "    private void helper() throws IOException {\n" +
            "        // helper method\n" +
            "    }\n" +
            "}\n";

    @Test
    public void testLocateClass() {
        SourceLocation location = locator.locateClass(
                "TestClass.java", "TestClass", TEST_SOURCE);

        assertNotNull(location);
        assertEquals("TestClass", location.getClassName());
        assertTrue(location.getStartLine() > 0);
        assertTrue(location.getEndLine() > location.getStartLine());
    }

    @Test
    public void testLocateClass_NotFound() {
        SourceLocation location = locator.locateClass(
                "TestClass.java", "NonExistent", TEST_SOURCE);

        assertNull(location);
    }

    @Test
    public void testLocateMethod_simpleMethod() {
        SourceLocation location = locator.locateMethod(
                "TestClass.java", "TestClass", "getName", TEST_SOURCE);

        assertNotNull(location);
        assertEquals("getName", location.getMethodName());
        assertTrue(location.getStartLine() > 0);
        assertTrue(location.getEndLine() > location.getStartLine());
        assertNotNull(location.getStyle());
    }

    @Test
    public void testLocateMethod_withParameters() {
        SourceLocation location = locator.locateMethod(
                "TestClass.java", "TestClass", "calculate", TEST_SOURCE);

        assertNotNull(location);
        assertEquals("calculate", location.getMethodName());
        assertTrue(location.getStartLine() > 0);
        assertTrue(location.getEndLine() > location.getStartLine());
    }

    @Test
    public void testLocateMethod_methodWithThrows() {
        SourceLocation location = locator.locateMethod(
                "TestClass.java", "TestClass", "helper", TEST_SOURCE);

        assertNotNull(location);
        assertEquals("helper", location.getMethodName());
    }

    @Test
    public void testLocateMethod_notFound() {
        SourceLocation location = locator.locateMethod(
                "TestClass.java", "TestClass", "nonExistent", TEST_SOURCE);

        assertNull(location);
    }

    @Test
    public void testFindImportInsertLine_normal() {
        String source = "package com.example;\n" +
                       "\n" +
                       "import java.util.List;\n" +
                       "import java.io.File;\n" +
                       "\n" +
                       "public class Test {}\n";

        int line = locator.findImportInsertLine(source);
        // After last import + blank lines before class
        assertTrue(line >= 3);
    }

    @Test
    public void testFindImportInsertLine_noImports() {
        String source = "package com.example;\n" +
                       "\n" +
                       "public class Test {}\n";

        int line = locator.findImportInsertLine(source);
        // Should return 0 (before package) or 1 (after package)
        assertTrue(line >= 0);
    }

    @Test
    public void testLocateMethod_constructor() {
        SourceLocation location = locator.locateMethod(
                "TestClass.java", "TestClass", "TestClass", TEST_SOURCE);

        assertNotNull(location);
    }

    @Test
    public void testLocateMethod_withReturnType() {
        String source = "public class Test {\n" +
                       "    public String getValue() {\n" +
                       "        return \"test\";\n" +
                       "    }\n" +
                       "}\n";

        SourceLocation location = locator.locateMethod(
                "Test.java", "Test", "getValue", source);

        assertNotNull(location);
        assertEquals("getValue", location.getMethodName());
    }

    @Test
    public void testLocateMethod_staticMethod() {
        String source = "public class Test {\n" +
                       "    public static void main(String[] args) {\n" +
                       "        System.out.println(\"hello\");\n" +
                       "    }\n" +
                       "}\n";

        SourceLocation location = locator.locateMethod(
                "Test.java", "Test", "main", source);

        assertNotNull(location);
        assertEquals("main", location.getMethodName());
    }
}
