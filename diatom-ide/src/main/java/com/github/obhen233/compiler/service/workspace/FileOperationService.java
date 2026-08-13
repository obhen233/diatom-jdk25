package com.github.obhen233.compiler.service.workspace;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.i18n.I18n;
import com.github.obhen233.compiler.service.ProjectIndexService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.*;

/**
 * File operation service - handles file/folder CRUD operations
 */
@Service
public class FileOperationService {

    @Autowired(required = false)
    private ProjectIndexService indexService;

    /**
     * Read file content
     */
    public Map<String, Object> readFile(String name, String path) {
        File file = resolveProjectFile(name, path);
        if (file == null || !file.exists() || file.isDirectory()) return fail(I18n.get("file.notFound", path));
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("content", new String(bytes, "UTF-8"));
            return result;
        } catch (Exception e) {
            return fail(I18n.get("file.readFail", e.getMessage()));
        }
    }

    /**
     * Save file content
     */
    public Map<String, Object> saveFile(String name, String path, String content) {
        File file = resolveProjectFile(name, path);
        if (file == null) return fail(I18n.get("file.pathInvalid"));
        file.getParentFile().mkdirs();
        try {
            writeText(file, content);
            // Notify index to update
            if (indexService != null && path != null) {
                indexService.notifyFileChanged(name, path, content);
            }
            return ok();
        } catch (Exception e) {
            return fail(I18n.get("file.saveFailed", e.getMessage()));
        }
    }

    /**
     * Create a new file
     */
    public Map<String, Object> createFile(String name, String parentPath, String fileName) {
        String trimmedFileName = fileName.trim();
        if (trimmedFileName.isEmpty()) return fail(I18n.get("file.notEmpty"));
        File parent = parentPath.isEmpty()
            ? new File(Constants.workspacePath, name)
            : resolveProjectFile(name, parentPath);
        if (parent == null || !parent.isDirectory()) return fail(I18n.get("file.parentNotExist"));
        File newFile = new File(parent, trimmedFileName);
        if (newFile.exists()) return fail(I18n.get("file.alreadyExists"));
        try {
            newFile.getParentFile().mkdirs();
            if (trimmedFileName.endsWith(".java")) {
                // Java file template
                String className = trimmedFileName.replace(".java", "");
                String pkg = guessPackage(new File(Constants.workspacePath, name), parent);
                StringBuilder sb = new StringBuilder();
                if (!pkg.isEmpty()) sb.append("package ").append(pkg).append(";\n\n");
                sb.append("public class ").append(className).append(" {\n\n}\n");
                writeText(newFile, sb.toString());
            } else if (trimmedFileName.equals("pom.xml")) {
                // Maven pom.xml template
                writeText(newFile, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                    "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                    "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                    "    <modelVersion>4.0.0</modelVersion>\n\n" +
                    "    <groupId>com.example</groupId>\n" +
                    "    <artifactId>" + name + "</artifactId>\n" +
                    "    <version>1.0-SNAPSHOT</version>\n\n" +
                    "    <dependencies>\n" +
                    "        <!-- Add dependencies here -->\n" +
                    "    </dependencies>\n" +
                    "</project>\n");
            } else if (trimmedFileName.equals("build.gradle")) {
                // Gradle build template
                writeText(newFile, "plugins {\n    id 'java'\n}\n\n" +
                    "group = 'com.example'\nversion = '1.0-SNAPSHOT'\n\n" +
                    "repositories {\n    mavenCentral()\n}\n\n" +
                    "dependencies {\n    // Add dependencies here\n}\n");
            } else {
                newFile.createNewFile();
            }
            // Return created file relative path
            File projectDir = new File(Constants.workspacePath, name);
            String createdPath = projectDir.toPath().relativize(newFile.toPath()).toString().replace('\\', '/');
            Map<String, Object> result = ok();
            result.put("path", createdPath);
            result.put("fileName", trimmedFileName);
            return result;
        } catch (Exception e) {
            return fail(I18n.get("file.createFail", e.getMessage()));
        }
    }

    /**
     * Create a new directory (regular folder)
     */
    public Map<String, Object> createDirectory(String name, String parentPath, String dirName) {
        String trimmedDirName = dirName.trim();
        if (trimmedDirName.isEmpty()) return fail(I18n.get("file.dirNameEmpty"));
        File parent = parentPath.isEmpty()
            ? new File(Constants.workspacePath, name)
            : resolveProjectFile(name, parentPath);
        if (parent == null || !parent.isDirectory()) return fail(I18n.get("file.parentNotExist"));
        File newDir = new File(parent, trimmedDirName);
        if (newDir.exists()) return fail(I18n.get("file.dirAlreadyExists"));
        return newDir.mkdirs() ? ok() : fail(I18n.get("file.dirCreateFail"));
    }

    /**
     * Rename file or directory
     */
    public Map<String, Object> renameFileOrDir(String name, String path, String newName) {
        if (path == null || newName == null || newName.trim().isEmpty()) return fail(I18n.get("common.requestFailed"));
        File file = resolveProjectFile(name, path);
        if (file == null || !file.exists()) return fail(I18n.get("file.notFound", path));
        File target = new File(file.getParentFile(), newName.trim());
        if (target.exists()) return fail(I18n.get("file.alreadyExists"));
        try {
            java.nio.file.Files.move(file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return ok();
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Cross-filesystem or atomic move not supported, fallback to regular move
            try {
                java.nio.file.Files.move(file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return ok();
            } catch (Exception e2) {
                return fail(I18n.get("file.renameFail", e2.getMessage()));
            }
        } catch (Exception e) {
            return fail(I18n.get("file.renameFail", e.getMessage()));
        }
    }

    /**
     * Delete file or directory
     */
    public Map<String, Object> deleteFileOrDir(String name, String path) {
        File file = resolveProjectFile(name, path);
        if (file == null || !file.exists()) return fail(I18n.get("file.notFound", path));
        boolean success;
        if (file.isDirectory()) {
            success = deleteRecursive(file);
        } else {
            success = file.delete();
        }
        if (success && indexService != null) {
            indexService.notifyFileDeleted(name, path);
        }
        return success ? ok() : fail(I18n.get("file.deleteFail"));
    }

    /**
     * Resolve a project-relative path to an absolute File, with security check
     */
    public File resolveProjectFile(String projectName, String path) {
        File projectDir = new File(Constants.workspacePath, projectName);
        if (path == null || path.isEmpty()) return projectDir;
        // Security check: prevent path traversal
        File resolved = new File(projectDir, path);

        // 检查符号链接，防止通过符号链接绕过路径限制
        try {
            if (java.nio.file.Files.isSymbolicLink(resolved.toPath())) {
                // 如果是符号链接，验证其目标在项目目录内
                Path realPath = resolved.toPath().toRealPath();
                if (!realPath.startsWith(projectDir.toPath().normalize())) {
                    return null;
                }
            } else {
                // 非符号链接，使用常规路径检查
                if (!resolved.toPath().normalize().startsWith(projectDir.toPath().normalize())) {
                    return null;
                }
            }
        } catch (IOException e) {
            // 无法解析路径，安全起见拒绝访问
            return null;
        }
        return resolved;
    }

    /**
     * Guess package name based on directory location
     */
    String guessPackage(File projectRoot, File dir) {
        // Try to recognize Maven/Gradle standard source directory structure
        String[] sourceRoots = {
            "src" + File.separator + "main" + File.separator + "java",
            "src" + File.separator + "test" + File.separator + "java",
            "src"
        };
        for (String root : sourceRoots) {
            File sourceRoot = new File(projectRoot, root);
            if (sourceRoot.exists() && dir.toPath().startsWith(sourceRoot.toPath())) {
                String rel = sourceRoot.toPath().relativize(dir.toPath()).toString();
                if (rel.isEmpty()) return "";
                return rel.replace(File.separatorChar, '.').replace('/', '.');
            }
        }
        return "";
    }

    /**
     * Write text content to file
     */
    void writeText(File file, String text) throws IOException {
        try (OutputStreamWriter osw = new OutputStreamWriter(
                new FileOutputStream(file), "UTF-8")) {
            osw.write(text);
        }
    }

    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        return file.delete();
    }

    Map<String, Object> ok() {
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        return r;
    }

    Map<String, Object> fail(String msg) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("message", msg);
        return r;
    }
}
