package com.github.obhen233.core.engine;

import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.HibernateConfig;
import com.github.obhen233.core.database.HibernateDatabaseManager;
import com.github.obhen233.core.database.CommandKnowledgeDao;
import com.github.obhen233.core.knowledge.CommandKnowledgeManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * CommandPermissionEngine 测试用例
 */
public class CommandPermissionEngineTest {

    private DatabaseManager db;
    private CommandKnowledgeManager knowledgeManager;
    private CommandPermissionEngine engine;

    @Before
    public void setUp() throws Exception {
        String testDbPath = Paths.get(System.getProperty("java.io.tmpdir"),
            "diatom_engine_test_" + System.currentTimeMillis() + ".db").toString();
        HibernateConfig config = new HibernateConfig("sqlite", "jdbc:sqlite:" + testDbPath, "", "", 2);
        db = new HibernateDatabaseManager(config);
        db.initialize();
        knowledgeManager = new CommandKnowledgeManager(db);
        knowledgeManager.loadFromDatabase();
        engine = new CommandPermissionEngine(knowledgeManager);
    }

    @After
    public void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    public void testBuiltinDangerousCommandsAreDenied() {
        // rm -rf with root path should always be denied
        CommandPermissionEngine.PermissionResult result = engine.checkPermission("rm -rf /");
        assertTrue(result.isDenied());
        assertEquals("Built-in dangerous command", result.reason);
    }

    @Test
    public void testHighRiskPatternsAreDenied() {
        // High risk patterns that should be denied
        // Using actual dangerous commands that are in the list
        CommandPermissionEngine.PermissionResult result = engine.checkPermission("rm -rf /*");
        assertTrue(result.isDenied());

        // dd command patterns should be denied
        result = engine.checkPermission("dd if=/dev/zero");
        assertTrue(result.isDenied());
    }

    @Test
    public void testEmptyCommandIsDenied() {
        CommandPermissionEngine.PermissionResult result = engine.checkPermission("");
        assertTrue(result.isDenied());

        result = engine.checkPermission(null);
        assertTrue(result.isDenied());
    }

    @Test
    public void testKnownCommandFromKnowledgeBase() {
        // Add a command to knowledge base
        knowledgeManager.learnCommand("kubectl get pods", "kubernetes", "ALLOW", 1, "learned");

        // Check permission
        CommandPermissionEngine.PermissionResult result = engine.checkPermission("kubectl get pods");
        assertTrue(result.isAllowed());
        assertEquals(1, result.riskLevel);
    }

    @Test
    public void testUnknownCommandNeedsEvaluation() {
        // Unknown command should need evaluation
        CommandPermissionEngine.PermissionResult result = engine.checkPermission("terraform apply");
        // This is an UNSURE command with low confidence
        assertTrue(result.needsEvaluation() || !result.isDenied());
    }

    @Test
    public void testReadOnlyGitCommands() {
        assertTrue(engine.isReadOnlyCommand("git status"));
        assertTrue(engine.isReadOnlyCommand("git log"));
        assertTrue(engine.isReadOnlyCommand("git show"));
        assertTrue(engine.isReadOnlyCommand("git diff"));
        assertTrue(engine.isReadOnlyCommand("git branch"));
        assertTrue(engine.isReadOnlyCommand("git stash list"));
    }

    @Test
    public void testReadOnlyShellCommands() {
        assertTrue(engine.isReadOnlyCommand("cat file.txt"));
        assertTrue(engine.isReadOnlyCommand("head -n 10 file.txt"));
        assertTrue(engine.isReadOnlyCommand("tail -f log.txt"));
        assertTrue(engine.isReadOnlyCommand("grep pattern file"));
        assertTrue(engine.isReadOnlyCommand("find . -name *.java"));
        assertTrue(engine.isReadOnlyCommand("ls -la"));
        assertTrue(engine.isReadOnlyCommand("pwd"));
    }

    @Test
    public void testNonReadOnlyCommands() {
        assertFalse(engine.isReadOnlyCommand("rm file.txt"));
        assertFalse(engine.isReadOnlyCommand("mv old.txt new.txt"));
        assertFalse(engine.isReadOnlyCommand("echo hello > file.txt"));
    }

    @Test
    public void testWriteOperations() {
        assertTrue(engine.containsWriteOperation("echo hello > file.txt"));
        assertTrue(engine.containsWriteOperation("echo hello >> file.txt"));
        assertTrue(engine.containsWriteOperation("cmd > output.txt"));
        assertTrue(engine.containsWriteOperation("cmd 2> error.txt"));
    }

    @Test
    public void testNoWriteOperations() {
        assertFalse(engine.containsWriteOperation("cat file.txt"));
        assertFalse(engine.containsWriteOperation("git status"));
        assertFalse(engine.containsWriteOperation("ls -la"));
    }

    @Test
    public void testInferToolType() {
        assertEquals("git", engine.inferToolType("git status"));
        assertEquals("git", engine.inferToolType("git"));
        assertEquals("maven", engine.inferToolType("mvn compile"));
        assertEquals("maven", engine.inferToolType("mvn"));
        assertEquals("npm", engine.inferToolType("npm install"));
        assertEquals("npm", engine.inferToolType("npx create-react-app"));
        assertEquals("docker", engine.inferToolType("docker ps"));
        assertEquals("java", engine.inferToolType("java -jar app.jar"));
        assertEquals("java", engine.inferToolType("javac Main.java"));
        assertEquals("python", engine.inferToolType("python script.py"));
        assertEquals("python", engine.inferToolType("python3 app.py"));
        assertEquals("go", engine.inferToolType("go build"));
        assertEquals("curl", engine.inferToolType("curl https://api.example.com"));
        assertEquals("wget", engine.inferToolType("wget https://example.com/file"));
        assertEquals("shell", engine.inferToolType("chmod 755 script.sh"));
        assertEquals("shell", engine.inferToolType("echo hello"));
        // docker-compose is not specifically detected, falls through to shell
        assertEquals("shell", engine.inferToolType("docker-compose up"));
    }

    @Test
    public void testNeedsLlmEvaluation() {
        // Add unknown command
        String unknownCmd = "ansible-playbook site.yml";

        // First check - should need evaluation (UNSURE with low confidence)
        assertTrue(engine.needsLlmEvaluation(unknownCmd));

        // After learning
        knowledgeManager.learnCommand(unknownCmd, "ansible", "ALLOW", 2, "learned");

        // Should not need evaluation anymore
        assertFalse(engine.needsLlmEvaluation(unknownCmd));
    }

    @Test
    public void testBuiltinDangerousDoesNotNeedLlmEvaluation() {
        // Built-in dangerous commands should not need LLM evaluation
        assertFalse(engine.needsLlmEvaluation("rm -rf /"));
        assertFalse(engine.needsLlmEvaluation("format c:"));
    }

    @Test
    public void testEvaluateAndLearn() {
        // Without LLM classifier set, should return false
        boolean result = engine.evaluateAndLearn("new_command");
        assertFalse(result);
    }

    @Test
    public void testPermissionResultIsAllowed() {
        CommandPermissionEngine.PermissionResult result = CommandPermissionEngine.PermissionResult.allow(0, 80, "test");
        assertTrue(result.isAllowed());
        assertFalse(result.isDenied());
        assertFalse(result.needsEvaluation());
    }

    @Test
    public void testPermissionResultAllowWithCaution() {
        CommandPermissionEngine.PermissionResult result = CommandPermissionEngine.PermissionResult.allowWithCaution(1, 70, "caution");
        assertTrue(result.isAllowed());
        assertFalse(result.isDenied());
    }

    @Test
    public void testPermissionResultDeny() {
        CommandPermissionEngine.PermissionResult result = CommandPermissionEngine.PermissionResult.deny("dangerous", 100);
        assertFalse(result.isAllowed());
        assertTrue(result.isDenied());
        assertFalse(result.needsEvaluation());
    }

    @Test
    public void testPermissionResultNeedsEvaluation() {
        CommandPermissionEngine.PermissionResult result = CommandPermissionEngine.PermissionResult.needsEvaluation("cmd", 1, 30, "unknown");
        assertFalse(result.isAllowed());
        assertFalse(result.isDenied());
        assertTrue(result.needsEvaluation());
        assertEquals("cmd", result.command);
    }

    @Test
    public void testRiskLevelBoundaries() {
        // Test that risk level is bounded 0-3
        CommandPermissionEngine.PermissionResult result = engine.checkPermission("echo test");
        // echo is either ALLOW or needs evaluation, but risk level should be valid
        assertTrue(result.riskLevel >= 0 && result.riskLevel <= 3);
    }
}
