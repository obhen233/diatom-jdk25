package com.github.obhen233.quarkus.runtime.kernel;

import com.github.obhen233.core.adapter.ResponsesAdapter;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.tool.ToolRegistry;
import com.github.obhen233.quarkus.runtime.TestConfigFactory;
import com.github.obhen233.quarkus.runtime.cloud.StorkWorkerRegistry;
import com.github.obhen233.quarkus.runtime.config.DiatomRuntimeConfig;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link DiatomKernel} 冒烟测试。
 *
 * <p>standard（纯引擎）模式：不起 HTTP 服务器，断言 ReActAgent / ToolRegistry 可用，
 * 内核可 start/stop 幂等。
 */
public class DiatomKernelSmokeTest {

    @Test
    public void standardModeAssemblesReActAgent() {
        DiatomRuntimeConfig config = TestConfigFactory.from(
                Map.of("diatom.mode", "standard"));
        DiatomKernel kernel = new DiatomKernel(config);

        kernel.start();
        try {
            assertEquals("standard", kernel.getMode());
            ReActAgent agent = kernel.reActAgent();
            assertNotNull("ReActAgent should be assembled in standard mode", agent);

            ToolRegistry registry = kernel.getShared().toolRegistry;
            assertNotNull("ToolRegistry should be available", registry);
            assertNotNull("Tool definitions should be registered",
                    registry.getToolDefinitions());
        } finally {
            kernel.stop();
        }
    }

    @Test
    public void startAndStopAreIdempotent() {
        DiatomRuntimeConfig config = TestConfigFactory.from(
                Map.of("diatom.mode", "standard"));
        DiatomKernel kernel = new DiatomKernel(config);

        kernel.start();
        kernel.start(); // second start is a no-op
        kernel.stop();
        kernel.stop(); // second stop is a no-op
    }

    @Test
    public void workerModeAssemblesAgentAndDegradedRegistration() {
        // gateway-url 指向不可达地址 → 注册/心跳优雅降级（不抛异常、不阻断启动）
        DiatomRuntimeConfig config = TestConfigFactory.from(Map.of(
                "diatom.mode", "worker",
                "diatom.worker.gateway-url", "http://127.0.0.1:1"));
        DiatomKernel kernel = new DiatomKernel(config);

        kernel.start();
        try {
            assertEquals("worker", kernel.getMode());
            assertNotNull("ReActAgent should be assembled in worker mode", kernel.reActAgent());
            assertNotNull("WorkerLoadState should be assembled", kernel.loadState());
            com.github.obhen233.quarkus.runtime.rest.QuarkusRegistrationService registration =
                    kernel.registrationService();
            assertNotNull("Registration service should be created", registration);
            assertNotNull("Worker id should be generated", registration.getWorkerId());
        } finally {
            kernel.stop();
        }
    }

    @Test
    public void apiConfigFlowsToAppConfig() {
        // diatom.api.* 必须映射进 AppConfig，LLM 链路才能读到 quarkus 配置
        DiatomRuntimeConfig config = TestConfigFactory.from(Map.of(
                "diatom.mode", "standard",
                "diatom.api.key", "sk-test",
                "diatom.api.base-url", "https://api.test.com",
                "diatom.api.model", "deepseek-chat"));
        DiatomKernel kernel = new DiatomKernel(config);

        kernel.start();
        try {
            assertEquals("sk-test", kernel.getShared().appConfig.getApiKey());
            assertEquals("https://api.test.com", kernel.getShared().appConfig.getBaseUrl());
            assertEquals("deepseek-chat", kernel.getShared().appConfig.getModel());
        } finally {
            kernel.stop();
        }
    }

    @Test
    public void responsesFormatCreatesResponsesAdapter() {
        // diatom.api.format=responses → 必须选 ResponsesAdapter（而非默认 OpenAIAdapter）
        DiatomRuntimeConfig config = TestConfigFactory.from(Map.of(
                "diatom.mode", "standard",
                "diatom.api.format", "responses"));
        DiatomKernel kernel = new DiatomKernel(config);

        kernel.start();
        try {
            assertTrue("modelAdapter should be ResponsesAdapter for api.format=responses",
                    kernel.getShared().modelAdapter instanceof ResponsesAdapter);
        } finally {
            kernel.stop();
        }
    }
}
