package com.github.obhen233.core.context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ProjectContext {
    private static final String NEWLINE = System.lineSeparator();

    private String projectName;
    private Path projectPath;
    private String projectType; // maven, gradle, npm, etc.
    private String directoryTree;
    private String buildFileContent;

    public ProjectContext() {
    }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public Path getProjectPath() { return projectPath; }
    public void setProjectPath(Path projectPath) { this.projectPath = projectPath; }

    public String getProjectType() { return projectType; }
    public void setProjectType(String projectType) { this.projectType = projectType; }

    public String getDirectoryTree() { return directoryTree; }
    public void setDirectoryTree(String directoryTree) { this.directoryTree = directoryTree; }

    public String getBuildFileContent() { return buildFileContent; }
    public void setBuildFileContent(String buildFileContent) { this.buildFileContent = buildFileContent; }

    public String buildContextSummary() {
        StringBuilder sb = new StringBuilder();

        sb.append("Project: ").append(projectName != null ? projectName : "unknown");
        sb.append(" (").append(projectType != null ? projectType : "unknown").append(")").append(NEWLINE);

        if (directoryTree != null && !directoryTree.isEmpty()) {
            sb.append("Directory structure:").append(NEWLINE);
            sb.append(directoryTree);
        }

        if (buildFileContent != null && !buildFileContent.isEmpty()) {
            sb.append(NEWLINE).append(buildFileContent);
        }

        return sb.toString();
    }
}
