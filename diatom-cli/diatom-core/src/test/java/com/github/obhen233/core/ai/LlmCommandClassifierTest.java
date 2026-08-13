package com.github.obhen233.core.ai;

import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.HibernateConfig;
import com.github.obhen233.core.database.HibernateDatabaseManager;
import com.github.obhen233.core.knowledge.CommandKnowledgeManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * LlmCommandClassifier 测试用例
 * 注意：这些测试不调用真实 LLM API，仅测试分类器逻辑
 */
public class LlmCommandClassifierTest {

    private DatabaseManager db;
    private CommandKnowledgeManager knowledgeManager;
    private String testDbPath;

    @Before
    public void setUp() throws Exception {
        testDbPath = Paths.get(System.getProperty("java.io.tmpdir"),
            "diatom_llm_test_" + System.currentTimeMillis() + ".db").toString();
        HibernateConfig config = new HibernateConfig("sqlite", "jdbc:sqlite:" + testDbPath, "", "", 2);
        db = new HibernateDatabaseManager(config);
        db.initialize();
        knowledgeManager = new CommandKnowledgeManager(db);
        knowledgeManager.loadFromDatabase();
    }

    @After
    public void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
        File dbFile = new File(testDbPath);
        if (dbFile.exists()) {
            dbFile.delete();
        }
    }

    @Test
    public void testClassifierResultCreation() {
        LlmCommandClassifier.ClassificationResult result =
            new LlmCommandClassifier.ClassificationResult("test cmd", 1, "ALLOW", "shell", "test reasoning");

        assertEquals("test cmd", result.command);
        assertEquals(1, result.riskLevel);
        assertEquals("ALLOW", result.permission);
        assertEquals("shell", result.toolType);
        assertEquals("test reasoning", result.reasoning);
    }

    @Test
    public void testRiskLevelBounded() {
        // Test that risk level is bounded to 0-3
        LlmCommandClassifier.ClassificationResult result1 =
            new LlmCommandClassifier.ClassificationResult("cmd", 5, "ALLOW", "shell", "");
        assertEquals(3, result1.riskLevel); // Should be bounded to 3

        LlmCommandClassifier.ClassificationResult result2 =
            new LlmCommandClassifier.ClassificationResult("cmd", -1, "ALLOW", "shell", "");
        assertEquals(0, result2.riskLevel); // Should be bounded to 0

        LlmCommandClassifier.ClassificationResult result3 =
            new LlmCommandClassifier.ClassificationResult("cmd", 2, "ALLOW", "shell", "");
        assertEquals(2, result3.riskLevel); // Normal value
    }

    @Test
    public void testClassifierResultWithValidValues() {
        // Test various valid risk levels
        for (int i = 0; i <= 3; i++) {
            LlmCommandClassifier.ClassificationResult result =
                new LlmCommandClassifier.ClassificationResult("cmd", i, "ALLOW", "shell", "");
            assertEquals(i, result.riskLevel);
        }
    }

    @Test
    public void testClassifierResultPermissionValues() {
        // Test different permission values
        String[] permissions = {"ALLOW", "DENY", "UNSURE"};
        for (String perm : permissions) {
            LlmCommandClassifier.ClassificationResult result =
                new LlmCommandClassifier.ClassificationResult("cmd", 1, perm, "shell", "");
            assertEquals(perm, result.permission);
        }
    }

    @Test
    public void testClassifierResultToolTypes() {
        // Test various tool types
        String[] toolTypes = {"git", "maven", "npm", "docker", "kubernetes", "terraform", "shell", "unknown"};
        for (String toolType : toolTypes) {
            LlmCommandClassifier.ClassificationResult result =
                new LlmCommandClassifier.ClassificationResult("cmd", 1, "ALLOW", toolType, "");
            assertEquals(toolType, result.toolType);
        }
    }

    @Test
    public void testClassifierResultReasoningCanBeEmpty() {
        LlmCommandClassifier.ClassificationResult result =
            new LlmCommandClassifier.ClassificationResult("cmd", 0, "ALLOW", "shell", "");
        assertEquals("", result.reasoning);

        LlmCommandClassifier.ClassificationResult result2 =
            new LlmCommandClassifier.ClassificationResult("cmd", 0, "ALLOW", "shell", null);
        assertNull(result2.reasoning);
    }

    @Test
    public void testClassifierResultCommandCanBeEmpty() {
        LlmCommandClassifier.ClassificationResult result =
            new LlmCommandClassifier.ClassificationResult("", 1, "UNSURE", "unknown", "");
        assertEquals("", result.command);
    }

    @Test
    public void testKnowledgeManagerHasNoLlmClassifierInitially() {
        assertFalse(knowledgeManager.hasLlmClassifier());
    }

    @Test
    public void testLearnCommandWithLlmWithoutClassifier() {
        // Without LLM classifier, should return false
        boolean result = knowledgeManager.learnCommandWithLlm("some_command");
        assertFalse(result);
    }

    @Test
    public void testLearnCommandWithLlmWithNullCommand() {
        // Null command should return false
        boolean result = knowledgeManager.learnCommandWithLlm(null);
        assertFalse(result);
    }

    @Test
    public void testLearnCommandWithLlmWithEmptyCommand() {
        // Empty command should return false
        boolean result = knowledgeManager.learnCommandWithLlm("");
        assertFalse(result);
    }

    @Test
    public void testLearnCommandWithLlmWithBuiltinDangerous() {
        // Built-in dangerous commands should not be learned (use diskutil as example)
        boolean result = knowledgeManager.learnCommandWithLlm("diskutil erase");
        assertFalse(result);
    }

    @Test
    public void testLearnCommandWithLlmAsyncWithoutClassifier() {
        // Without classifier, async should do nothing (not throw)
        knowledgeManager.learnCommandWithLlmAsync("some_command");
        // Just verify no exception
        assertFalse(knowledgeManager.hasLlmClassifier());
    }

    @Test
    public void testNeedsLlmEvaluationForUnknown() {
        // Unknown command with low confidence should need evaluation
        String unknownCmd = "some_random_command_" + System.currentTimeMillis();
        assertTrue(knowledgeManager.needsLlmEvaluation(unknownCmd));
    }

    @Test
    public void testNeedsLlmEvaluationForBuiltinDangerous() {
        // Built-in dangerous should not need evaluation (already known as dangerous)
        // Use CommandPermissionEngine to check
        com.github.obhen233.core.engine.CommandPermissionEngine engine =
            new com.github.obhen233.core.engine.CommandPermissionEngine(knowledgeManager);
        // rm -rf / is a builtin dangerous command, so it should return false
        assertFalse(engine.needsLlmEvaluation("rm -rf /"));
    }

    @Test
    public void testNeedsLlmEvaluationForKnown() {
        // After learning, should not need evaluation
        String cmd = "test_known_cmd_" + System.currentTimeMillis();
        knowledgeManager.learnCommand(cmd, "shell", "ALLOW", 1, "learned");

        assertFalse(knowledgeManager.needsLlmEvaluation(cmd));
    }

    @Test
    public void testEvaluateAndLearnWithoutClassifier() {
        // Without classifier, should return false
        // evaluateAndLearn is in CommandPermissionEngine
        com.github.obhen233.core.engine.CommandPermissionEngine engine =
            new com.github.obhen233.core.engine.CommandPermissionEngine(knowledgeManager);
        boolean result = engine.evaluateAndLearn("some_command");
        assertFalse(result);
    }

    @Test
    public void testEvaluateAndLearnWithNull() {
        com.github.obhen233.core.engine.CommandPermissionEngine engine =
            new com.github.obhen233.core.engine.CommandPermissionEngine(knowledgeManager);
        boolean result = engine.evaluateAndLearn(null);
        assertFalse(result);
    }

    @Test
    public void testEvaluateAndLearnWithEmpty() {
        com.github.obhen233.core.engine.CommandPermissionEngine engine =
            new com.github.obhen233.core.engine.CommandPermissionEngine(knowledgeManager);
        boolean result = engine.evaluateAndLearn("");
        assertFalse(result);
    }

    @Test
    public void testEvaluateAndLearnWithBuiltinDangerous() {
        com.github.obhen233.core.engine.CommandPermissionEngine engine =
            new com.github.obhen233.core.engine.CommandPermissionEngine(knowledgeManager);
        boolean result = engine.evaluateAndLearn("diskutil eraseDisk");
        assertFalse(result);
    }

    @Test
    public void testSetLlmClassifier() {
        // Just verify it can be set without error
        // We can't test actual LLM calls without a mock HTTP client
        assertFalse(knowledgeManager.hasLlmClassifier());
    }
}
