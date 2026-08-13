package com.github.obhen233.compiler.entity;

import jakarta.persistence.*;

/**
 * Debug configuration entity for saving and loading debug session settings.
 * Supports multiple launch modes: MAIN_CLASS, MAVEN, GRADLE, SPRING_BOOT, GRADLE_BOOT, ATTACH.
 */
@Entity
@Table(name = "debug_configurations")
public class DebugConfiguration {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "project_name", length = 256)
    private String projectName;

    @Column(name = "name", length = 128)
    private String name;

    @Column(name = "launch_mode", length = 32)
    private String launchMode; // MAIN_CLASS, MAVEN, GRADLE, SPRING_BOOT, GRADLE_BOOT, ATTACH

    @Column(name = "main_class", length = 512)
    private String mainClass;

    @Column(name = "spring_boot_main_class", length = 512)
    private String springBootMainClass;

    @Column(name = "gradle_task", length = 256)
    private String gradleTask;

    @Column(name = "attach_port")
    private Integer attachPort;

    @Column(name = "jvm_args", length = 1024)
    private String jvmArgs;

    @Column(name = "program_args", length = 1024)
    private String programArgs;

    @Column(name = "auto_compile")
    private boolean autoCompile;

    @Column(name = "suspend")
    private boolean suspend;

    public DebugConfiguration() {
        this.id = java.util.UUID.randomUUID().toString();
        this.autoCompile = true;
        this.suspend = true;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getLaunchMode() { return launchMode; }
    public void setLaunchMode(String launchMode) { this.launchMode = launchMode; }

    public String getMainClass() { return mainClass; }
    public void setMainClass(String mainClass) { this.mainClass = mainClass; }

    public String getSpringBootMainClass() { return springBootMainClass; }
    public void setSpringBootMainClass(String springBootMainClass) { this.springBootMainClass = springBootMainClass; }

    public String getGradleTask() { return gradleTask; }
    public void setGradleTask(String gradleTask) { this.gradleTask = gradleTask; }

    public Integer getAttachPort() { return attachPort; }
    public void setAttachPort(Integer attachPort) { this.attachPort = attachPort; }

    public String getJvmArgs() { return jvmArgs; }
    public void setJvmArgs(String jvmArgs) { this.jvmArgs = jvmArgs; }

    public String getProgramArgs() { return programArgs; }
    public void setProgramArgs(String programArgs) { this.programArgs = programArgs; }

    public boolean isAutoCompile() { return autoCompile; }
    public void setAutoCompile(boolean autoCompile) { this.autoCompile = autoCompile; }

    public boolean isSuspend() { return suspend; }
    public void setSuspend(boolean suspend) { this.suspend = suspend; }
}
