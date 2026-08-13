package com.github.obhen233.compiler.service.workspace;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.i18n.I18n;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.file.Paths;
import java.util.*;

/**
 * Project management service - handles project CRUD operations
 */
@Service
public class ProjectManagementService {

    @Autowired
    private com.github.obhen233.compiler.service.ClasspathBuilder classpathBuilder;

    /**
     * List all projects in workspace
     */
    public Map<String, Object> listProjects() {
        Map<String, Object> result = new HashMap<>();
        File wsDir = new File(Constants.workspacePath);
        List<Map<String, Object>> projects = new ArrayList<>();
        if (wsDir.exists() && wsDir.isDirectory()) {
            File[] files = wsDir.listFiles(File::isDirectory);
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File f : files) {
                    Map<String, Object> p = new HashMap<>();
                    p.put("name", f.getName());
                    p.put("type", detectProjectType(f));
                    p.put("vcsType", detectVcsType(f));
                    projects.add(p);
                }
            }
        }
        result.put("projects", projects);
        result.put("workspacePath", Constants.workspacePath);
        return result;
    }

    /**
     * Create a new project
     */
    public Map<String, Object> createProject(Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        String name = body.containsKey("name") ? (String) body.get("name") : null;
        if (name == null || name.trim().isEmpty()) {
            return fail(I18n.get("project.nameEmpty"));
        }
        name = name.trim();
        // Security check: prevent path traversal
        if (name.contains("..") || name.contains("/") || name.contains("\\") || name.contains(File.separator)) {
            return fail(I18n.get("project.nameInvalid", name));
        }
        File projectDir = new File(Constants.workspacePath, name);
        if (!projectDir.toPath().normalize().startsWith(Paths.get(Constants.workspacePath).normalize())) {
            return fail(I18n.get("project.nameInvalid", name));
        }
        if (projectDir.exists()) {
            return fail(I18n.get("project.alreadyExists"));
        }

        String projectType = body.containsKey("type") ? (String) body.get("type") : "simple";
        @SuppressWarnings("unchecked")
        List<String> modules = body.containsKey("modules") ? (List<String>) body.get("modules") : new ArrayList<>();

        switch (projectType) {
            case "maven-multi":
                createMavenMultiModuleProject(projectDir, name, modules);
                break;
            case "gradle-multi":
                createGradleMultiProject(projectDir, name, modules);
                break;
            default:
                createSimpleProject(projectDir, name);
                break;
        }

        result.put("success", true);
        result.put("name", name);
        result.put("type", projectType);
        return result;
    }

    private void createSimpleProject(File projectDir, String name) {
        projectDir.mkdirs();
        File srcDir = new File(projectDir, "src");
        srcDir.mkdirs();
        new File(projectDir, "lib").mkdirs();
        try {
            writeText(new File(srcDir, "Main.java"),
                "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, " + name + "!\");\n    }\n}\n");
        } catch (Exception e) {
            throw new RuntimeException(I18n.get("file.createFailed", e.getMessage()));
        }
    }

    private void createMavenMultiModuleProject(File projectDir, String parentName, List<String> modules) {
        projectDir.mkdirs();
        if (modules.isEmpty()) {
            modules.add(parentName + "-api");
            modules.add(parentName + "-core");
        }

        // Create parent pom.xml (packaging pom + modules)
        StringBuilder parentPom = new StringBuilder();
        parentPom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        parentPom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        parentPom.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        parentPom.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        parentPom.append("    <modelVersion>4.0.0</modelVersion>\n");
        parentPom.append("    <groupId>com.example</groupId>\n");
        parentPom.append("    <artifactId>").append(parentName).append("</artifactId>\n");
        parentPom.append("    <version>1.0-SNAPSHOT</version>\n");
        parentPom.append("    <packaging>pom</packaging>\n");
        parentPom.append("    <name>").append(parentName).append("</name>\n\n");
        parentPom.append("    <properties>\n");
        parentPom.append("        <maven.compiler.source>8</maven.compiler.source>\n");
        parentPom.append("        <maven.compiler.target>8</maven.compiler.target>\n");
        parentPom.append("        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n");
        parentPom.append("    </properties>\n\n");
        parentPom.append("    <modules>\n");
        for (String module : modules) {
            parentPom.append("        <module>").append(module).append("</module>\n");
        }
        parentPom.append("    </modules>\n");
        parentPom.append("</project>\n");
        try {
            writeText(new File(projectDir, "pom.xml"), parentPom.toString());
        } catch (Exception e) {
            throw new RuntimeException(I18n.get("file.createFailed", e.getMessage()));
        }

        // Create sub-modules
        for (String module : modules) {
            File moduleDir = new File(projectDir, module);
            moduleDir.mkdirs();
            File srcMain = new File(moduleDir, "src/main/java/com/example/" + module);
            srcMain.mkdirs();
            new File(moduleDir, "src/test/java/com/example/" + module).mkdirs();
            new File(moduleDir, "lib").mkdirs();

            // Sub-module pom.xml
            StringBuilder modulePom = new StringBuilder();
            modulePom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            modulePom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
            modulePom.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            modulePom.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
            modulePom.append("    <modelVersion>4.0.0</modelVersion>\n");
            modulePom.append("    <parent>\n");
            modulePom.append("        <groupId>com.example</groupId>\n");
            modulePom.append("        <artifactId>").append(parentName).append("</artifactId>\n");
            modulePom.append("        <version>1.0-SNAPSHOT</version>\n");
            modulePom.append("    </parent>\n");
            modulePom.append("    <artifactId>").append(module).append("</artifactId>\n");
            modulePom.append("    <name>").append(module).append("</name>\n");
            modulePom.append("</project>\n");
            try {
                writeText(new File(moduleDir, "pom.xml"), modulePom.toString());
                writeText(new File(srcMain, module.replaceAll("[^a-zA-Z0-9]", "") + ".java"),
                    "package com.example." + module + ";\n\npublic class " + module.replaceAll("[^a-zA-Z0-9]", "") + " {\n    public String greet() {\n        return \"Hello from " + module + "!\";\n    }\n}\n");
            } catch (Exception e) {
                throw new RuntimeException(I18n.get("file.createFailed", e.getMessage()));
            }
        }
    }

    private void createGradleMultiProject(File projectDir, String parentName, List<String> modules) {
        projectDir.mkdirs();
        if (modules.isEmpty()) {
            modules.add(parentName + "-api");
            modules.add(parentName + "-core");
        }

        // settings.gradle
        StringBuilder settings = new StringBuilder();
        settings.append("rootProject.name = '").append(parentName).append("'\n\n");
        for (String module : modules) {
            settings.append("include '").append(module).append("'\n");
        }
        try {
            writeText(new File(projectDir, "settings.gradle"), settings.toString());
        } catch (Exception e) {
            throw new RuntimeException(I18n.get("file.createFailed", e.getMessage()));
        }

        // Create sub-modules
        for (String module : modules) {
            File moduleDir = new File(projectDir, module);
            moduleDir.mkdirs();
            File srcMain = new File(moduleDir, "src/main/java/com/example/" + module);
            srcMain.mkdirs();
            new File(moduleDir, "src/test/java/com/example/" + module).mkdirs();
            new File(moduleDir, "lib").mkdirs();

            // build.gradle
            try {
                writeText(new File(moduleDir, "build.gradle"),
                    "plugins {\n    id 'java'\n}\n\n" +
                    "group = 'com.example'\n" +
                    "version = '1.0-SNAPSHOT'\n\n" +
                    "repositories {\n    mavenCentral()\n}\n\n" +
                    "dependencies {\n    testImplementation 'junit:junit:4.13.2'\n}\n");
                writeText(new File(srcMain, module.replaceAll("[^a-zA-Z0-9]", "") + ".java"),
                    "package com.example." + module + ";\n\npublic class " + module.replaceAll("[^a-zA-Z0-9]", "") + " {\n    public String greet() {\n        return \"Hello from " + module + "!\";\n    }\n}\n");
            } catch (Exception e) {
                throw new RuntimeException(I18n.get("file.createFailed", e.getMessage()));
            }
        }
    }

    /**
     * Rename a project
     */
    public Map<String, Object> renameProject(String name, String newName) {
        if (newName == null || newName.trim().isEmpty()) return fail(I18n.get("project.nameEmpty"));
        newName = newName.trim();
        // Security check: prevent path traversal
        if (newName.contains("..") || newName.contains("/") || newName.contains("\\")) {
            return fail(I18n.get("project.nameInvalid", newName));
        }
        File oldDir = new File(Constants.workspacePath, name);
        File newDir = new File(Constants.workspacePath, newName);
        if (!newDir.toPath().normalize().startsWith(Paths.get(Constants.workspacePath).normalize())) {
            return fail(I18n.get("project.nameInvalid", newName));
        }
        if (!oldDir.exists()) return fail(I18n.get("project.notFound"));
        if (newDir.exists()) return fail(I18n.get("project.targetExists"));
        try {
            java.nio.file.Files.move(oldDir.toPath(), newDir.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return ok();
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            try {
                java.nio.file.Files.move(oldDir.toPath(), newDir.toPath());
                return ok();
            } catch (Exception e2) {
                return fail(I18n.get("project.renameFailed", e2.getMessage()));
            }
        } catch (Exception e) {
            return fail(I18n.get("project.renameFailed", e.getMessage()));
        }
    }

    /**
     * Delete a project (or move to removed folder)
     */
    public Map<String, Object> deleteProject(String name, String mode) {
        File projectDir = new File(Constants.workspacePath, name);
        if (!projectDir.exists()) return fail(I18n.get("project.notFound"));
        if ("delete".equals(mode)) {
            return deleteRecursive(projectDir) ? ok() : fail(I18n.get("project.deleteFail"));
        } else {
            File removedDir = new File(Constants.workspacePath + "_removed");
            if (!removedDir.exists()) removedDir.mkdirs();
            File target = new File(removedDir, name + "_" + System.currentTimeMillis());
            try {
                java.nio.file.Files.move(projectDir.toPath(), target.toPath());
                return ok();
            } catch (Exception e) {
                return fail(I18n.get("project.removeFail", e.getMessage()));
            }
        }
    }

    /**
     * List projects that have been removed from workspace
     */
    public Map<String, Object> listRemovedProjects() {
        Map<String, Object> result = new HashMap<>();
        File removedDir = new File(Constants.workspacePath + "_removed");
        List<Map<String, String>> items = new ArrayList<>();
        if (removedDir.exists() && removedDir.isDirectory()) {
            File[] files = removedDir.listFiles(File::isDirectory);
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File f : files) {
                    Map<String, String> item = new HashMap<>();
                    // Directory name format: originalName_timestamp
                    String dirName = f.getName();
                    int lastUnderscore = dirName.lastIndexOf('_');
                    String originalName = lastUnderscore > 0 ? dirName.substring(0, lastUnderscore) : dirName;
                    item.put("dirName", dirName);
                    item.put("originalName", originalName);
                    items.add(item);
                }
            }
        }
        result.put("success", true);
        result.put("removed", items);
        return result;
    }

    /**
     * Restore a removed project back to workspace
     */
    public Map<String, Object> restoreProject(String dirName) {
        if (dirName == null || dirName.trim().isEmpty()) return fail(I18n.get("common.requestFailed"));
        File removedDir = new File(Constants.workspacePath + "_removed");
        File source = new File(removedDir, dirName);
        if (!source.exists()) return fail(I18n.get("project.notFound"));
        // Restore to original project name
        int lastUnderscore = dirName.lastIndexOf('_');
        String originalName = lastUnderscore > 0 ? dirName.substring(0, lastUnderscore) : dirName;
        File target = new File(Constants.workspacePath, originalName);
        // If same name project exists, add suffix
        if (target.exists()) {
            int i = 1;
            while (target.exists()) {
                target = new File(Constants.workspacePath, originalName + "_" + i);
                i++;
            }
        }
        try {
            java.nio.file.Files.move(source.toPath(), target.toPath());
            Map<String, Object> result = ok();
            result.put("name", target.getName());
            return result;
        } catch (Exception e) {
            return fail(I18n.get("project.removeFail", e.getMessage()));
        }
    }

    /**
     * Detect project type (maven, gradle, or plain)
     */
    public String detectProjectType(File projectDir) {
        if (new File(projectDir, "pom.xml").exists()) return "maven";
        if (new File(projectDir, "build.gradle").exists()) return "gradle";
        return "plain";
    }

    private String detectVcsType(File projectDir) {
        if (new File(projectDir, ".svn").exists()) return "svn";
        if (new File(projectDir, ".git").exists()) return "git";
        return "none";
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

    void writeText(File file, String text) throws IOException {
        try (OutputStreamWriter osw = new OutputStreamWriter(
                new FileOutputStream(file), "UTF-8")) {
            osw.write(text);
        }
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
