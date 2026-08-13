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
import com.github.obhen233.quarkus.runtime.TestConfigFactory;
import com.github.obhen233.quarkus.runtime.config.DiatomRuntimeConfig;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * {@link DiatomComponents} 测试：stub config → {@code @Produces} 共享 Bean 非 null。
 *
 * <p>纯 JUnit 直接实例化（不依赖 Quarkus 上下文），验证生产者返回 kernel 持有的同一实例。
 */
public class DiatomComponentsTest {

    @Test
    public void producesAllSharedBeansNonNull() {
        DiatomRuntimeConfig config = TestConfigFactory.from(
                Map.of("diatom.mode", "standard"));
        DiatomComponents components = new DiatomComponents(config);

        AppConfig appConfig = components.appConfig();
        SystemInfo systemInfo = components.systemInfo();
        AuthorizedPathManager auth = components.authorizedPathManager();
        SkillManager skillManager = components.skillManager();
        SystemPromptManager promptManager = components.systemPromptManager();
        ProjectIndexer projectIndexer = components.projectIndexer();
        McpClientManager mcpManager = components.mcpClientManager();
        AiHttpClient httpClient = components.aiHttpClient();
        ModelAdapter modelAdapter = components.modelAdapter();
        CommandTools.Config commandConfig = components.commandConfig();
        ToolRegistryCenter center = components.toolRegistryCenter();
        ToolRegistry registry = components.toolRegistry();

        assertNotNull(appConfig);
        assertNotNull(systemInfo);
        assertNotNull(auth);
        assertNotNull(skillManager);
        assertNotNull(promptManager);
        assertNotNull(projectIndexer);
        assertNotNull(mcpManager);
        assertNotNull(httpClient);
        assertNotNull(modelAdapter);
        assertNotNull(commandConfig);
        assertNotNull(center);
        assertNotNull(registry);

        // 幂等：多次获取返回 kernel 持有的同一实例
        assertSame(appConfig, components.appConfig());
        assertSame(registry, components.toolRegistry());
    }
}
