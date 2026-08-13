package com.github.obhen233.core.code;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/**
 * CodeModificationProtocol 测试
 */
public class CodeModificationProtocolTest {

    private final CodeModificationProtocol protocol = new CodeModificationProtocol();

    @Test
    public void testParseJson_replaceMethodBody() {
        String json = "{\n" +
                     "  \"type\": \"REPLACE_METHOD_BODY\",\n" +
                     "  \"target\": \"TestClass.method\",\n" +
                     "  \"newCode\": \"System.out.println();\",\n" +
                     "  \"file\": \"Test.java\"\n" +
                     "}";

        List<ModificationInstruction> instructions = protocol.parse(json, "Test.java");

        assertEquals(1, instructions.size());

        ModificationInstruction instruction = instructions.get(0);
        assertEquals(ModificationInstruction.ModificationType.REPLACE_METHOD_BODY, instruction.getType());
        assertEquals("TestClass.method", instruction.getTarget());
        assertEquals("System.out.println();", instruction.getNewCode());
        assertEquals("Test.java", instruction.getFilePath());
    }

    @Test
    public void testParseJson_addImport() {
        String json = "{\n" +
                     "  \"type\": \"ADD_IMPORT\",\n" +
                     "  \"target\": \"java.util.List\",\n" +
                     "  \"newCode\": \"import java.util.List;\",\n" +
                     "  \"file\": \"Test.java\"\n" +
                     "}";

        List<ModificationInstruction> instructions = protocol.parse(json, "Test.java");

        assertEquals(1, instructions.size());
        assertEquals(ModificationInstruction.ModificationType.ADD_IMPORT, instructions.get(0).getType());
    }

    @Test
    public void testParseJson_multipleTypes() {
        String json = "{\n" +
                     "  \"type\": \"ADD_METHOD\",\n" +
                     "  \"target\": \"TestClass\",\n" +
                     "  \"newCode\": \"public void newMethod() {}\"\n" +
                     "}";

        List<ModificationInstruction> instructions = protocol.parse(json, "Test.java");

        assertEquals(1, instructions.size());
        assertEquals(ModificationInstruction.ModificationType.ADD_METHOD, instructions.get(0).getType());
    }

    @Test
    public void testParseTextFormat_basic() {
        String textResponse = "TYPE: REPLACE_METHOD_BODY\n" +
                            " TARGET: TestClass.method\n" +
                            "NEW_CODE:\n" +
                            "System.out.println(\"new\");";

        List<ModificationInstruction> instructions = protocol.parse(textResponse, "Test.java");

        assertEquals(1, instructions.size());
        assertEquals(ModificationInstruction.ModificationType.REPLACE_METHOD_BODY, instructions.get(0).getType());
        assertEquals("TestClass.method", instructions.get(0).getTarget());
        assertTrue(instructions.get(0).getNewCode().contains("System.out.println"));
    }

    @Test
    public void testParseTextFormat_withFile() {
        String textResponse = "TYPE: ADD_IMPORT\n" +
                            "FILE: MyFile.java\n" +
                            "TARGET: java.util.List\n" +
                            "NEW_CODE: import java.util.List;";

        List<ModificationInstruction> instructions = protocol.parse(textResponse, "Default.java");

        assertEquals(1, instructions.size());
        assertEquals("MyFile.java", instructions.get(0).getFilePath());
    }

    @Test
    public void testParseTextFormat_withCodeBlock() {
        String textResponse = "TYPE: ADD_METHOD\n" +
                            "TARGET: TestClass\n" +
                            "NEW_CODE:\n" +
                            "```java\n" +
                            "public void newMethod() {\n" +
                            "    System.out.println();\n" +
                            "}\n" +
                            "```";

        List<ModificationInstruction> instructions = protocol.parse(textResponse, "Test.java");

        assertEquals(1, instructions.size());
        assertEquals(ModificationInstruction.ModificationType.ADD_METHOD, instructions.get(0).getType());
        assertTrue(instructions.get(0).getNewCode().contains("public void newMethod"));
    }

    @Test
    public void testParseEmptyResponse() {
        List<ModificationInstruction> instructions = protocol.parse("", "Test.java");
        assertTrue(instructions.isEmpty());
    }

    @Test
    public void testGeneratePrompt() {
        CodeStructureSummary summary = new CodeStructureSummary();
        summary.setFilePath("Test.java");
        summary.setPackageName("com.example");
        summary.setClassName("TestClass");
        summary.setClassType("class");

        CodeStructureSummary.MemberSummary member = new CodeStructureSummary.MemberSummary();
        member.setName("method1");
        member.setType("METHOD");
        member.setApproximateLine(10);
        summary.addMember(member);

        String prompt = protocol.generatePrompt("Add new functionality", summary);

        assertTrue(prompt.contains("Task: Add new functionality"));
        assertTrue(prompt.contains("File: Test.java"));
        assertTrue(prompt.contains("Class: TestClass"));
        assertTrue(prompt.contains("TYPE:"));
        assertTrue(prompt.contains("TARGET:"));
    }

    @Test
    public void testParseJson_withEscapedCharacters() {
        String json = "{\n" +
                     "  \"type\": \"REPLACE_METHOD_BODY\",\n" +
                     "  \"target\": \"Test.method\",\n" +
                     "  \"newCode\": \"System.out.println(\\\"hello\\\");\"\n" +
                     "}";

        List<ModificationInstruction> instructions = protocol.parse(json, "Test.java");

        assertEquals(1, instructions.size());
        assertTrue(instructions.get(0).getNewCode().contains("System.out.println"));
    }

    @Test
    public void testParseTypeVariations_replaceMethodBody() {
        // Test various type string formats for REPLACE_METHOD_BODY
        List<ModificationInstruction> result1 = protocol.parse(
                "{\"type\":\"replace_method_body\",\"target\":\"Test.method\"}", "Test.java");
        assertEquals(1, result1.size());
        assertEquals(ModificationInstruction.ModificationType.REPLACE_METHOD_BODY, result1.get(0).getType());
    }

    @Test
    public void testParseTypeVariations_addImport() {
        List<ModificationInstruction> result = protocol.parse(
                "{\"type\":\"ADD-IMPORT\",\"target\":\"java.util.List\"}", "Test.java");
        assertEquals(1, result.size());
        assertEquals(ModificationInstruction.ModificationType.ADD_IMPORT, result.get(0).getType());
    }

    @Test
    public void testParseTypeVariations_addMethod() {
        List<ModificationInstruction> result = protocol.parse(
                "{\"type\":\"add method\",\"target\":\"TestClass\"}", "Test.java");
        assertEquals(1, result.size());
        assertEquals(ModificationInstruction.ModificationType.ADD_METHOD, result.get(0).getType());
    }
}
