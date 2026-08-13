package com.github.obhen233.quarkus.runtime.rest;

import com.github.obhen233.quarkus.runtime.TestConfigFactory;
import com.github.obhen233.quarkus.runtime.components.DiatomComponents;
import com.github.obhen233.quarkus.runtime.components.DiatomRuntimeContext;
import com.github.obhen233.quarkus.runtime.config.DiatomRuntimeConfig;
import jakarta.ws.rs.core.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link WorkerResource} 测试（纯 JUnit：真实 core 组件）。
 *
 * <p>worker 模式：验证 health（含注册 workerId）、缺 message 的 400 分支，
 * 以及准入控制（loadState 满 → 503）。真实 {@code agent.run} 需 LLM，不在此触发。</p>
 */
public class WorkerResourceTest {

    private DiatomRuntimeContext context;
    private WorkerResource resource;

    @Before
    public void setUp() {
        // 不配置 gateway-url → 跳过直连注册（start 仅打日志，不启线程）
        DiatomRuntimeConfig config = TestConfigFactory.from(Map.of("diatom.mode", "worker"));
        DiatomComponents components = new DiatomComponents(config);
        context = new DiatomRuntimeContext(components);
        resource = new WorkerResource(context);
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.kernel().stop();
        }
    }

    @Test
    public void healthReportsUpWithWorkerId() {
        Response res = resource.handleHealth();
        assertEquals(200, res.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getEntity();
        assertEquals("UP", body.get("status"));
        QuarkusRegistrationService registration = context.kernel().registrationService();
        assertNotNull(registration);
        assertEquals(registration.getWorkerId(), body.get("workerId"));
        assertNotNull("workerId should not be blank", body.get("workerId"));
    }

    @Test
    public void chatMissingMessageReturns400() {
        assertEquals(400, resource.handleChat(Map.of()).getStatus());
        assertEquals(400, resource.handleChat(null).getStatus());
    }

    @Test
    public void chatOverloadedReturns503() {
        // 占满唯一并发槽位（默认 maxConcurrency=1）→ 下次调用直接 503，不触达 agent.run
        assertTrue(context.loadState().tryAcquire());
        try {
            Response res = resource.handleChat(Map.of("message", "hello", "taskId", "t1"));
            assertEquals(503, res.getStatus());
        } finally {
            context.loadState().release();
        }
    }

    @Test
    public void handleCommandDispatchesToRegistry() {
        // help 命令不依赖 agent/DB，经 CoreCommandRegistry 执行
        Response help = resource.handleCommand(Map.of("command", "help"));
        assertEquals(200, help.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> helpBody = (Map<String, Object>) help.getEntity();
        assertEquals("ok", helpBody.get("status"));
        assertNotNull("help output should not be empty", helpBody.get("output"));
        assertTrue("help output should be non-empty", ((String) helpBody.get("output")).length() > 0);

        // 前导 / 应被剥离
        Response slashHelp = resource.handleCommand(Map.of("command", "/help"));
        assertEquals(200, slashHelp.getStatus());

        // 未注册命令 → 404
        Response missing = resource.handleCommand(Map.of("command", "no-such-command"));
        assertEquals(404, missing.getStatus());

        // 空 body / 缺 command → 400
        assertEquals(400, resource.handleCommand(Map.of()).getStatus());
        assertEquals(400, resource.handleCommand(null).getStatus());
    }
}
