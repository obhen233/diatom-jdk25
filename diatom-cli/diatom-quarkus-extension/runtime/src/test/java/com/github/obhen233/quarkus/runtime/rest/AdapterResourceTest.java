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

/**
 * {@link AdapterResource} 测试（纯 JUnit）。
 *
 * <p>adapter 模式：classpath 上无 {@code AgentAdapter} 驱动（ServiceLoader 未发现），
 * 验证 {@code /worker/v1/chat} 返回 503、health 返回 DOWN（driverType=none）。</p>
 */
public class AdapterResourceTest {

    private DiatomRuntimeContext context;
    private AdapterResource resource;

    @Before
    public void setUp() {
        DiatomRuntimeConfig config = TestConfigFactory.from(Map.of("diatom.mode", "adapter"));
        DiatomComponents components = new DiatomComponents(config);
        context = new DiatomRuntimeContext(components);
        resource = new AdapterResource(context);
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.kernel().stop();
        }
    }

    @Test
    public void chatWithoutDriverReturns503() {
        Response res = resource.handleChat(Map.of("message", "hello", "taskId", "t1"));
        assertEquals(503, res.getStatus());
    }

    @Test
    public void healthReportsDownWithoutDriver() {
        Response res = resource.handleHealth();
        assertEquals(200, res.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getEntity();
        assertEquals("DOWN", body.get("status"));
        assertEquals("none", body.get("driverType"));
    }
}
