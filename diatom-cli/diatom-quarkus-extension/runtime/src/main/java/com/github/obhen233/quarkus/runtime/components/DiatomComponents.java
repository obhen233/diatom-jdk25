package com.github.obhen233.quarkus.runtime.components;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.config.SystemInfo;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.context.ProjectIndexer;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.mcp.McpClientManager;
import com.github.obhen233.core.skill.SkillManager;
import com.github.obhen233.core.skill.SystemPromptManager;
import com.github.obhen233.core.tool.AuthorizedPathManager;
import com.github.obhen233.core.tool.ToolRegistry;
import com.github.obhen233.core.tool.ToolRegistryCenter;
import com.github.obhen233.core.tool.builtin.CommandTools;
import com.github.obhen233.quarkus.runtime.config.DiatomRuntimeConfig;
import com.github.obhen233.quarkus.runtime.kernel.DiatomKernel;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * CDI 组件生产者层。
 *
 * <p>懒加载 {@link DiatomKernel}（首次访问或 Startup 事件时启动），并 {@code @Produces}
 * 暴露始终可用的共享组件。可空/模式服务<b>不</b>在此单独 @Produces（ArC normal-scoped
 * 返回 null 不安全），改走 {@link DiatomRuntimeContext} 门面 getter。
 *
 * <p>注意：core 组件多为无 no-arg 构造器的普通类（如 {@link AiHttpClient}），
 * 用 normal-scoped 生产者会因无法生成 client proxy 而构建失败，因此各生产者方法
 * 不标注作用域 —— 默认 {@code @Dependent}，直接注入 kernel 持有的同一实例（等价单例）。
 */
@ApplicationScoped
public class DiatomComponents {

    private final DiatomRuntimeConfig config;
    private volatile DiatomKernel kernel;
    private volatile boolean kernelStarted;

    @Inject
    public DiatomComponents(DiatomRuntimeConfig config) {
        this.config = config;
    }

    /** 懒加载并启动内核（幂等；Startup/Shutdown 观察者也调用它）。 */
    public synchronized DiatomKernel kernel() {
        if (kernel == null) {
            kernel = new DiatomKernel(config);
        }
        if (!kernelStarted) {
            kernel.start();
            kernelStarted = true;
        }
        return kernel;
    }

    // ===== 始终可用共享组件生产者 =====

    @Produces
    @Unremovable
    public AppConfig appConfig() {
        return kernel().getShared().appConfig;
    }

    @Produces
    @Unremovable
    public SystemInfo systemInfo() {
        return kernel().getShared().systemInfo;
    }

    @Produces
    @Unremovable
    public AuthorizedPathManager authorizedPathManager() {
        return kernel().getShared().authorizedPathManager;
    }

    @Produces
    @Unremovable
    public SkillManager skillManager() {
        return kernel().getShared().skillManager;
    }

    @Produces
    @Unremovable
    public SystemPromptManager systemPromptManager() {
        return kernel().getShared().systemPromptManager;
    }

    @Produces
    @Unremovable
    public ProjectIndexer projectIndexer() {
        return kernel().getShared().projectIndexer;
    }

    @Produces
    @Unremovable
    public McpClientManager mcpClientManager() {
        return kernel().getShared().mcpClientManager;
    }

    @Produces
    @Unremovable
    public AiHttpClient aiHttpClient() {
        return kernel().getShared().aiHttpClient;
    }

    @Produces
    @Unremovable
    public ModelAdapter modelAdapter() {
        return kernel().getShared().modelAdapter;
    }

    @Produces
    @Unremovable
    public CommandTools.Config commandConfig() {
        return kernel().getShared().commandConfig;
    }

    @Produces
    @Unremovable
    public ToolRegistryCenter toolRegistryCenter() {
        return kernel().getShared().toolRegistryCenter;
    }

    @Produces
    @Unremovable
    public ToolRegistry toolRegistry() {
        return kernel().getShared().toolRegistry;
    }
}
