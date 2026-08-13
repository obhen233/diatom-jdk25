package com.github.obhen233.config;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * SystemInfo 测试用例
 * 对应 TEST_CASES.md 11. SystemInfo 测试
 */
public class SystemInfoTest {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    @Test
    public void testSystemInfoCreation() {
        SystemInfo info = new SystemInfo();

        assertNotNull(info.getOsName());
        assertNotNull(info.getOsVersion());
        assertNotNull(info.getOsArch());
        assertNotNull(info.getJavaVersion());
        assertNotNull(info.getJavaHome());
        assertNotNull(info.getUserHome());
        assertNotNull(info.getUserDir());
        assertTrue(info.getAvailableProcessors() > 0);
        assertTrue(info.getMaxMemory() > 0);
    }

    @Test
    public void testOsDetection() {
        SystemInfo info = new SystemInfo();

        if (IS_WINDOWS) {
            assertTrue(info.isWindows());
            assertFalse(info.isLinux());
            assertFalse(info.isMac());
            assertEquals("Windows", info.getOsType());
        }
    }

    @Test
    public void testDetectedShellOnWindows() {
        if (!IS_WINDOWS) {
            System.out.println("Skipping shell detection test on non-Windows system");
            return;
        }

        SystemInfo info = new SystemInfo();

        // Shell 检测应该在 Windows 上工作
        String shellType = info.getShellType();
        assertNotNull(shellType);

        if (info.getDetectedShell() != null) {
            assertTrue("Shell type should be valid",
                shellType.equals("git_bash") || shellType.equals("mingw64") ||
                shellType.equals("cygwin") || shellType.equals("wsl2") ||
                shellType.equals("cmd"));
        } else {
            assertEquals("cmd", shellType);
        }
    }

    @Test
    public void testDetectedPythonOnWindows() {
        if (!IS_WINDOWS) {
            System.out.println("Skipping Python detection test on non-Windows system");
            return;
        }

        SystemInfo info = new SystemInfo();

        // 如果 PYTHON_HOME 设置，Python 应该被检测
        String pythonHome = System.getenv("PYTHON_HOME");
        if (pythonHome != null) {
            assertNotNull("Python should be detected when PYTHON_HOME is set",
                info.getDetectedPython());
        }
    }

    @Test
    public void testDetectedNodeOnWindows() {
        if (!IS_WINDOWS) {
            System.out.println("Skipping Node.js detection test on non-Windows system");
            return;
        }

        SystemInfo info = new SystemInfo();

        // 如果 NODE_HOME 设置，Node.js 应该被检测
        String nodeHome = System.getenv("NODE_HOME");
        if (nodeHome != null) {
            assertNotNull("Node.js should be detected when NODE_HOME is set",
                info.getDetectedNode());
        }
    }

    @Test
    public void testBuildSummary() {
        SystemInfo info = new SystemInfo();
        String summary = info.buildSummary();

        assertNotNull(summary);
        // New compact format: "OS: Windows(amd64) | Dir: ... | ..."
        assertTrue(summary.contains("OS:"));
        assertTrue(summary.contains("Dir:"));
    }

    @Test
    public void testBuildSummaryIncludesShellOnWindows() {
        if (!IS_WINDOWS) {
            System.out.println("Skipping shell in summary test on non-Windows system");
            return;
        }

        SystemInfo info = new SystemInfo();
        String summary = info.buildSummary();

        // New compact format includes Shell only if non-cmd shell detected
        if (info.getDetectedShell() != null && !"cmd".equals(info.getShellType())) {
            assertTrue(summary.contains("Shell:"));
        }
        // If cmd or no shell detected, summary should still be valid
        assertNotNull(summary);
        assertTrue(summary.contains("OS:"));
    }

    @Test
    public void testBuildSummaryIncludesPythonOnWindows() {
        if (!IS_WINDOWS) {
            System.out.println("Skipping Python in summary test on non-Windows system");
            return;
        }

        SystemInfo info = new SystemInfo();
        String summary = info.buildSummary();

        // 如果 Python 被检测到，摘要应包含 Python 信息
        if (info.getDetectedPython() != null) {
            assertTrue(summary.contains("Python:"));
        }
    }

    @Test
    public void testBuildSummaryIncludesNodeOnWindows() {
        if (!IS_WINDOWS) {
            System.out.println("Skipping Node.js in summary test on non-Windows system");
            return;
        }

        SystemInfo info = new SystemInfo();
        String summary = info.buildSummary();

        // 如果 Node.js 被检测到，摘要应包含 Node 信息
        if (info.getDetectedNode() != null) {
            assertTrue(summary.contains("Node:"));
        }
    }

    @Test
    public void testMemoryFormatting() {
        SystemInfo info = new SystemInfo();

        // getMaxMemory 应该返回有效的内存值
        long maxMemory = info.getMaxMemory();
        assertTrue(maxMemory > 0);

        // getTotalMemory 应该返回有效的内存值
        long totalMemory = info.getTotalMemory();
        assertTrue(totalMemory > 0);

        // totalMemory 不应大于 maxMemory
        assertTrue(totalMemory <= maxMemory);
    }

    @Test
    public void testGetters() {
        SystemInfo info = new SystemInfo();

        // 验证所有 getter 方法都返回非 null 值（对于基本字段）
        assertNotNull(info.getOsName());
        assertNotNull(info.getOsVersion());
        assertNotNull(info.getOsArch());
        assertNotNull(info.getJavaVersion());
        assertNotNull(info.getJavaHome());
        assertNotNull(info.getUserHome());
        assertNotNull(info.getUserDir());
        assertTrue(info.getAvailableProcessors() > 0);
        assertTrue(info.getMaxMemory() > 0);
    }
}
