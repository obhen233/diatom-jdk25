package com.github.obhen233.compiler.constant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;

/**
 * author: obhen233
 * date:  2026/04/27
 * desc: 配置常量
 *
 * 线程安全说明:
 * classPath 和 workspacePath 在 @PostConstruct 初始化后不再修改（视为不可变配置）。
 * volatile 确保多线程间的可见性：写 happens-before 读。
 * 对于"一次写入，多次读取"的场景，volatile 是充分且必要的。
 */
@Component
public class Constants {

    public static final String clasName = "Main";
    public static final String executeMainMethodName = "main";

    // 使用 volatile 保证多线程可见性：初始化后只读
    public static volatile String classPath;
    public static volatile String workspacePath;

    /** 当前 IDE 配置的编译目标 JDK 版本 (5-25)，由 IdeSettingsController 同步，默认 25 */
    public static volatile int jdkVersion = 25;

    @Value("${workspace.path:${WORKSPACE_PATH:${user.dir}/workspace}}")
    private String configuredWorkspacePath;

    @PostConstruct
    public void init() {
        if (configuredWorkspacePath != null && !configuredWorkspacePath.trim().isEmpty()) {
            workspacePath = configuredWorkspacePath.trim();
        } else {
            workspacePath = System.getProperty("user.dir") + File.separator + "workspace";
        }
        // 确保工作区目录存在
        File wsDir = new File(workspacePath);
        if (!wsDir.exists()) {
            wsDir.mkdirs();
        }
        // classPath 指向工作区
        classPath = workspacePath + File.separator;
    }
}
