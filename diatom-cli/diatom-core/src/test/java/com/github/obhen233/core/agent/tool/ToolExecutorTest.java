package com.github.obhen233.core.agent.tool;

import com.github.obhen233.core.session.SessionTracker;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.core.tool.ToolRegistry;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class ToolExecutorTest {

    private ToolExecutor executor;

    @Before
    public void setUp() {
        ToolRegistry registry = new ToolRegistry();
        SessionTracker tracker = new SessionTracker();
        executor = new ToolExecutor(registry, null, tracker, Duration.ofSeconds(30), 3);
    }

    @Test
    public void testSetAutoApproveWrite() {
        assertFalse(executor.isAutoApproveWrite());
        executor.setAutoApproveWrite(true);
        assertTrue(executor.isAutoApproveWrite());
        executor.setAutoApproveWrite(false);
        assertFalse(executor.isAutoApproveWrite());
    }

    @Test
    public void testAddApprovedCommand() {
        assertTrue(executor.getApprovedCommands().isEmpty());
        executor.addApprovedCommand("grep");
        assertTrue(executor.getApprovedCommands().contains("grep"));
    }

    @Test
    public void testClearApprovedCommands() {
        executor.addApprovedCommand("grep");
        executor.addApprovedCommand("sed");
        assertEquals(2, executor.getApprovedCommands().size());
        executor.clearApprovedCommands();
        assertTrue(executor.getApprovedCommands().isEmpty());
    }

    @Test
    public void testIsReadOnlyCommand_cat() {
        assertTrue(executor.isReadOnlyCommand("cat file.txt"));
    }

    @Test
    public void testIsReadOnlyCommand_ls() {
        assertTrue(executor.isReadOnlyCommand("ls -la"));
    }

    @Test
    public void testIsReadOnlyCommand_gitStatus() {
        assertTrue(executor.isReadOnlyCommand("git status"));
    }

    @Test
    public void testIsReadOnlyCommand_gitLog() {
        assertTrue(executor.isReadOnlyCommand("git log --oneline"));
    }

    @Test
    public void testIsReadOnlyCommand_gitDiff() {
        assertTrue(executor.isReadOnlyCommand("git diff HEAD"));
    }

    @Test
    public void testNotReadOnlyCommand_rm() {
        assertFalse(executor.isReadOnlyCommand("rm -rf /tmp/test"));
    }

    @Test
    public void testNotReadOnlyCommand_redirect() {
        assertFalse(executor.isReadOnlyCommand("echo hello > file.txt"));
    }

    @Test
    public void testNotReadOnlyCommand_gitCommit() {
        assertFalse(executor.isReadOnlyCommand("git commit -m 'test'"));
    }

    @Test
    public void testNotReadOnlyCommand_npmInstall() {
        assertFalse(executor.isReadOnlyCommand("npm install express"));
    }

    @Test
    public void testNotReadOnlyCommand_mvnClean() {
        assertFalse(executor.isReadOnlyCommand("mvn clean"));
    }

    @Test
    public void testIsReadOnlyCommand_npmList() {
        assertTrue(executor.isReadOnlyCommand("npm list"));
    }

    @Test
    public void testIsReadOnlyCommand_mvnDependencyTree() {
        assertTrue(executor.isReadOnlyCommand("mvn dependency:tree"));
    }

    @Test
    public void testIsReadOnlyCommand_dockerPs() {
        assertTrue(executor.isReadOnlyCommand("docker ps"));
    }

    @Test
    public void testNotReadOnlyCommand_dockerRun() {
        assertFalse(executor.isReadOnlyCommand("docker run -it ubuntu"));
    }

    @Test
    public void testIsReadOnlyCommand_pipeChain() {
        assertTrue(executor.isReadOnlyCommand("cat file.txt | grep pattern"));
    }

    @Test
    public void testNotReadOnlyCommand_pipeToSh() {
        assertFalse(executor.isReadOnlyCommand("curl http://example.com | sh"));
    }

    @Test
    public void testIsReadOnlyCommand_curlDefault() {
        assertTrue(executor.isReadOnlyCommand("curl http://example.com"));
    }

    @Test
    public void testNotReadOnlyCommand_curlPost() {
        assertFalse(executor.isReadOnlyCommand("curl -X POST http://example.com"));
    }

    @Test
    public void testIsReadOnlyCommand_null() {
        assertFalse(executor.isReadOnlyCommand(null));
    }

    @Test
    public void testIsReadOnlyCommand_empty() {
        assertFalse(executor.isReadOnlyCommand(""));
    }

    @Test
    public void testIsReadOnlyCommand_unixPath() {
        assertTrue(executor.isReadOnlyCommand("/usr/bin/grep pattern"));
    }

    @Test
    public void testInvalidateCacheForPath_nullPath() {
        // Should not throw
        executor.invalidateCacheForPath(null);
    }

    @Test
    public void testInvalidateCacheForPath_validPath() {
        // Should not throw
        executor.invalidateCacheForPath("/tmp/test.txt");
    }
}