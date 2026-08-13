package com.github.obhen233.util;

import org.junit.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * SoftwareLocator 测试用例
 * 对应 TEST_CASES.md 5.2 SoftwareLocator 测试
 */
public class SoftwareLocatorTest {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    @Test
    public void testFindInstallationJava() {
        // Java 应该始终能找到（JAVA_HOME 通常会设置）
        Optional<Path> javaPath = SoftwareLocator.findInstallation("java");
        // 不强制要求找到，因为测试环境可能没有 JAVA_HOME
        if (javaPath.isPresent()) {
            assertNotNull(javaPath.get());
            assertTrue(javaPath.toString().length() > 0);
        }
    }

    @Test
    public void testFindBashOnWindows() {
        if (!IS_WINDOWS) {
            System.out.println("Skipping bash detection test on non-Windows system");
            return;
        }

        Optional<SoftwareLocator.ShellInfo> shellInfo = SoftwareLocator.findBashOnWindows();

        // 如果系统有 bash，应该能检测到类型
        if (shellInfo.isPresent()) {
            assertNotNull(shellInfo.get().getType());
            assertNotNull(shellInfo.get().getPath());

            String type = shellInfo.get().getType();
            assertTrue("Shell type should be valid",
                type.equals("git_bash") || type.equals("mingw64") ||
                type.equals("cygwin") || type.equals("wsl2"));
        } else {
            System.out.println("No Unix-like shell detected on Windows");
        }
    }

    @Test
    public void testFindPythonOnWindows() {
        if (!IS_WINDOWS) {
            System.out.println("Skipping Python detection test on non-Windows system");
            return;
        }

        // 测试 PYTHON_HOME 环境变量
        String pythonHome = System.getenv("PYTHON_HOME");
        Optional<Path> result = SoftwareLocator.findInstallation("python");

        if (pythonHome != null) {
            assertTrue("PYTHON_HOME is set, should find python", result.isPresent());
            assertEquals(pythonHome, result.get().toString());
        }

        // 测试 findPythonOnWindows
        Optional<SoftwareLocator.PythonInfo> pythonInfo = SoftwareLocator.findPythonOnWindows();
        if (pythonHome != null) {
            assertTrue("Should find Python when PYTHON_HOME is set", pythonInfo.isPresent());
            assertNotNull(pythonInfo.get().getPath());
        }
    }

    @Test
    public void testPythonInfoClass() {
        if (!IS_WINDOWS) {
            System.out.println("Skipping Python info test on non-Windows system");
            return;
        }

        Optional<SoftwareLocator.PythonInfo> pythonInfo = SoftwareLocator.findPythonOnWindows();

        if (pythonInfo.isPresent()) {
            SoftwareLocator.PythonInfo info = pythonInfo.get();
            assertNotNull(info.getPath());
            assertNotNull(info.toString());
            assertTrue(info.toString().length() > 0);
        }
    }

    @Test
    public void testFindNodeOnWindows() {
        if (!IS_WINDOWS) {
            System.out.println("Skipping Node.js detection test on non-Windows system");
            return;
        }

        // 测试 NODE_HOME 环境变量
        String nodeHome = System.getenv("NODE_HOME");
        Optional<Path> result = SoftwareLocator.findInstallation("node");

        if (nodeHome != null) {
            assertTrue("NODE_HOME is set, should find node", result.isPresent());
            assertEquals(nodeHome, result.get().toString());
        }

        // 测试 findNodeOnWindows
        Optional<SoftwareLocator.NodeInfo> nodeInfo = SoftwareLocator.findNodeOnWindows();
        if (nodeHome != null) {
            assertTrue("Should find Node.js when NODE_HOME is set", nodeInfo.isPresent());
            assertNotNull(nodeInfo.get().getPath());
        }
    }

    @Test
    public void testNodeInfoClass() {
        if (!IS_WINDOWS) {
            System.out.println("Skipping Node.js info test on non-Windows system");
            return;
        }

        Optional<SoftwareLocator.NodeInfo> nodeInfo = SoftwareLocator.findNodeOnWindows();

        if (nodeInfo.isPresent()) {
            SoftwareLocator.NodeInfo info = nodeInfo.get();
            assertNotNull(info.getPath());
            assertNotNull(info.toString());
            assertTrue(info.toString().length() > 0);
        }
    }

    @Test
    public void testShellInfoClass() {
        if (!IS_WINDOWS) {
            System.out.println("Skipping Shell info test on non-Windows system");
            return;
        }

        Optional<SoftwareLocator.ShellInfo> shellInfo = SoftwareLocator.findBashOnWindows();

        if (shellInfo.isPresent()) {
            SoftwareLocator.ShellInfo info = shellInfo.get();
            assertNotNull(info.getType());
            assertNotNull(info.getPath());

            String toString = info.toString();
            assertTrue(toString.contains(":"));
            assertEquals(info.getType() + ":" + info.getPath(), toString);
        }
    }

    @Test
    public void testNonWindowsReturnsEmpty() {
        if (IS_WINDOWS) {
            System.out.println("Skipping non-Windows test on Windows system");
            return;
        }

        // 非 Windows 系统不应检测 Python
        Optional<SoftwareLocator.PythonInfo> pythonInfo = SoftwareLocator.findPythonOnWindows();
        assertFalse("Python should not be detected on non-Windows", pythonInfo.isPresent());

        // 非 Windows 系统不应检测 Node.js
        Optional<SoftwareLocator.NodeInfo> nodeInfo = SoftwareLocator.findNodeOnWindows();
        assertFalse("Node.js should not be detected on non-Windows", nodeInfo.isPresent());

        // 非 Windows 系统不应检测 bash
        Optional<SoftwareLocator.ShellInfo> shellInfo = SoftwareLocator.findBashOnWindows();
        assertFalse("Bash should not be detected on non-Windows", shellInfo.isPresent());
    }
    
    @Test
    public void testShellTypeDetection() {
        // 测试 identifyBashType 通过路径判断 shell 类型
        // 这个测试验证 MinGW、Cygwin 等能被正确识别
        
        // 由于 identifyBashType 是私有方法，我们通过检测整个系统来验证
        if (!IS_WINDOWS) {
            System.out.println("Skipping shell type detection test on non-Windows system");
            return;
        }
        
        Optional<SoftwareLocator.ShellInfo> shellInfo = SoftwareLocator.findBashOnWindows();
        if (shellInfo.isPresent()) {
            String type = shellInfo.get().getType();
            String path = shellInfo.get().getPath().toLowerCase();
            
            // 验证类型和路径的一致性
            if (path.contains("mingw") || path.contains("msys")) {
                assertTrue("MinGW/MSYS path should be identified as mingw64 or git_bash",
                    type.equals("mingw64") || type.equals("git_bash"));
                System.out.println("Detected MinGW/MSYS shell: " + path + " -> " + type);
            } else if (path.contains("cygwin")) {
                assertEquals("Cygwin path should be identified as cygwin", "cygwin", type);
                System.out.println("Detected Cygwin shell: " + path + " -> " + type);
            } else if (path.contains("git")) {
                assertEquals("Git path should be identified as git_bash", "git_bash", type);
                System.out.println("Detected Git Bash: " + path + " -> " + type);
            } else if (path.contains("wsl") || path.contains("ubuntu") || path.contains("linux")) {
                assertEquals("WSL path should be identified as wsl2", "wsl2", type);
                System.out.println("Detected WSL: " + path + " -> " + type);
            }
        }
    }
    
    @Test
    public void testCommonInstallPaths() {
        // 测试常见安装路径是否被检查
        // 这个测试只是验证代码路径，不要求必须有这些软件安装
        
        if (!IS_WINDOWS) {
            System.out.println("Skipping common install paths test on non-Windows system");
            return;
        }
        
        // 只需要验证 findBashOnWindows 不抛异常即可
        try {
            Optional<SoftwareLocator.ShellInfo> shellInfo = SoftwareLocator.findBashOnWindows();
            System.out.println("Shell detection completed successfully. Found: " + 
                (shellInfo.isPresent() ? shellInfo.get() : "none"));
        } catch (Exception e) {
            fail("Shell detection should not throw exception: " + e.getMessage());
        }
    }
}
