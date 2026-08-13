package com.github.obhen233.core.database;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.*;

/**
 * CommandKnowledgeDao 测试用例
 */
public class CommandKnowledgeDaoTest {

    private DatabaseManager db;
    private CommandKnowledgeDao dao;
    private String testDbPath;

    @Before
    public void setUp() throws Exception {
        testDbPath = Paths.get(System.getProperty("java.io.tmpdir"),
            "diatom_cmd_knowledge_test_" + System.currentTimeMillis() + ".db").toString();
        HibernateConfig config = new HibernateConfig("sqlite", "jdbc:sqlite:" + testDbPath, "", "", 2);
        db = new HibernateDatabaseManager(config);
        db.initialize();
        dao = new CommandKnowledgeDao(db);
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
    public void testInsertAndFind() {
        // Insert a command knowledge
        CommandKnowledgeDao.CommandKnowledge knowledge = new CommandKnowledgeDao.CommandKnowledge(
            "git status", "git", "ALLOW", 0);
        knowledge.source = "builtin";
        knowledge.confidence = 100;
        dao.insertCommandKnowledge(knowledge);

        // Find by command
        CommandKnowledgeDao.CommandKnowledge found = dao.findByCommand("git status");
        assertNotNull(found);
        assertEquals("git status", found.command);
        assertEquals("git", found.toolType);
        assertEquals("ALLOW", found.permission);
        assertEquals(0, found.riskLevel);
        assertEquals("builtin", found.source);
    }

    @Test
    public void testFindByPermission() {
        // Insert test commands
        insertCommand("git status", "git", "ALLOW", 0);
        insertCommand("git push", "git", "ALLOW", 2);
        insertCommand("diskutil erase", "shell", "DENY", 3);

        // Find ALLOW commands
        List<CommandKnowledgeDao.CommandKnowledge> allowList = dao.findByPermission("ALLOW");
        assertEquals(2, allowList.size());

        // Find DENY commands
        List<CommandKnowledgeDao.CommandKnowledge> denyList = dao.findByPermission("DENY");
        assertEquals(1, denyList.size());
        assertEquals("diskutil erase", denyList.get(0).command);
    }

    @Test
    public void testFindByToolType() {
        // Insert test commands
        insertCommand("git status", "git", "ALLOW", 0);
        insertCommand("git push", "git", "ALLOW", 2);
        insertCommand("mvn compile", "maven", "ALLOW", 1);

        // Find git commands
        List<CommandKnowledgeDao.CommandKnowledge> gitCommands = dao.findByToolType("git");
        assertEquals(2, gitCommands.size());

        // Find maven commands
        List<CommandKnowledgeDao.CommandKnowledge> mavenCommands = dao.findByToolType("maven");
        assertEquals(1, mavenCommands.size());
    }

    @Test
    public void testFindBySource() {
        // Insert test commands
        insertCommand("git status", "git", "ALLOW", 0);
        insertCommand("kubectl get pods", "kubernetes", "ALLOW", 1);

        // Update the second one to be "learned"
        CommandKnowledgeDao.CommandKnowledge found = dao.findByCommand("kubectl get pods");
        found.source = "learned";
        dao.updateCommandKnowledge(found);

        // Find builtin commands
        List<CommandKnowledgeDao.CommandKnowledge> builtinCommands = dao.findBySource("builtin");
        assertEquals(1, builtinCommands.size());

        // Find learned commands
        List<CommandKnowledgeDao.CommandKnowledge> learnedCommands = dao.findBySource("learned");
        assertEquals(1, learnedCommands.size());
    }

    @Test
    public void testFindByMinRiskLevel() {
        // Insert test commands
        insertCommand("git status", "git", "ALLOW", 0);
        insertCommand("mvn install", "maven", "ALLOW", 2);
        insertCommand("rm -rf /", "shell", "DENY", 3);

        // Find commands with risk level >= 2
        List<CommandKnowledgeDao.CommandKnowledge> riskyCommands = dao.findByMinRiskLevel(2);
        assertEquals(2, riskyCommands.size());
    }

    @Test
    public void testFindFrequentlyUsed() {
        // Insert test commands
        insertCommand("git status", "git", "ALLOW", 0);
        insertCommand("mvn compile", "maven", "ALLOW", 1);

        // Increment verified count
        dao.incrementVerifiedCount("git status");
        dao.incrementVerifiedCount("git status");
        dao.incrementVerifiedCount("mvn compile");

        // Find frequently used
        List<CommandKnowledgeDao.CommandKnowledge> frequent = dao.findFrequentlyUsed(10);
        assertTrue(frequent.size() >= 2);
        // git status should be first (count=2)
        assertEquals("git status", frequent.get(0).command);
    }

    @Test
    public void testFindLowConfidence() {
        // Insert test commands with different confidence
        insertCommandWithConfidence("git status", "git", "ALLOW", 0, 90);
        insertCommandWithConfidence("kubectl get pods", "kubernetes", "ALLOW", 1, 40);
        insertCommandWithConfidence("terraform plan", "terraform", "UNSURE", 1, 30);

        // Find low confidence
        List<CommandKnowledgeDao.CommandKnowledge> lowConf = dao.findLowConfidence(10);
        assertTrue(lowConf.size() >= 2);
        // Should be sorted by confidence ascending
        assertTrue(lowConf.get(0).confidence < lowConf.get(1).confidence);
    }

    @Test
    public void testUpdateConfidence() {
        // Insert command
        insertCommand("kubectl get pods", "kubernetes", "ALLOW", 1);

        // Update confidence
        dao.updateConfidence("kubectl get pods", 10);

        // Verify
        CommandKnowledgeDao.CommandKnowledge found = dao.findByCommand("kubectl get pods");
        assertNotNull(found);
        assertEquals(60, found.confidence); // 50 + 10
    }

    @Test
    public void testIncrementVerifiedCount() {
        // Insert command
        insertCommand("docker ps", "docker", "ALLOW", 0);

        // Increment
        dao.incrementVerifiedCount("docker ps");
        dao.incrementVerifiedCount("docker ps");

        // Verify
        CommandKnowledgeDao.CommandKnowledge found = dao.findByCommand("docker ps");
        assertNotNull(found);
        assertEquals(2, found.verifiedCount);
    }

    @Test
    public void testDeleteCommand() {
        // Insert command
        insertCommand("to_be_deleted", "shell", "ALLOW", 0);

        // Verify exists
        CommandKnowledgeDao.CommandKnowledge found = dao.findByCommand("to_be_deleted");
        assertNotNull(found);

        // Delete
        dao.deleteCommand("to_be_deleted");

        // Verify deleted
        found = dao.findByCommand("to_be_deleted");
        assertNull(found);
    }

    @Test
    public void testGetCount() {
        // Insert some commands
        insertCommand("cmd1", "shell", "ALLOW", 0);
        insertCommand("cmd2", "shell", "ALLOW", 0);
        insertCommand("cmd3", "shell", "ALLOW", 0);

        assertEquals(3, dao.getCount());
    }

    @Test
    public void testGetCountBySource() {
        insertCommand("cmd1", "shell", "ALLOW", 0);
        insertCommand("cmd2", "shell", "ALLOW", 0);

        CommandKnowledgeDao.CommandKnowledge found = dao.findByCommand("cmd1");
        found.source = "learned";
        dao.updateCommandKnowledge(found);

        assertEquals(1, dao.getCountBySource("builtin"));
        assertEquals(1, dao.getCountBySource("learned"));
    }

    @Test
    public void testFindAll() {
        insertCommand("cmd1", "shell", "ALLOW", 0);
        insertCommand("cmd2", "shell", "ALLOW", 0);

        List<CommandKnowledgeDao.CommandKnowledge> all = dao.findAll();
        assertTrue(all.size() >= 2);
    }

    @Test
    public void testFindUnknown() {
        insertCommand("unknown_cmd", "shell", "UNSURE", 1);
        insertCommand("known_cmd", "shell", "ALLOW", 0);

        List<CommandKnowledgeDao.CommandKnowledge> unknown = dao.findUnknown();
        assertTrue(unknown.size() >= 1);
        assertEquals("unknown_cmd", unknown.get(0).command);
    }

    private void insertCommand(String command, String toolType, String permission, int riskLevel) {
        CommandKnowledgeDao.CommandKnowledge knowledge = new CommandKnowledgeDao.CommandKnowledge(
            command, toolType, permission, riskLevel);
        knowledge.source = "builtin";
        knowledge.confidence = 50;
        dao.insertCommandKnowledge(knowledge);
    }

    private void insertCommandWithConfidence(String command, String toolType, String permission, int riskLevel, int confidence) {
        CommandKnowledgeDao.CommandKnowledge knowledge = new CommandKnowledgeDao.CommandKnowledge(
            command, toolType, permission, riskLevel);
        knowledge.source = "builtin";
        knowledge.confidence = confidence;
        dao.insertCommandKnowledge(knowledge);
    }
}
