package com.github.obhen233.quarkus.runtime.rest;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.quarkus.runtime.TestConfigFactory;
import com.github.obhen233.quarkus.runtime.components.DiatomComponents;
import com.github.obhen233.quarkus.runtime.components.DiatomRuntimeContext;
import com.github.obhen233.quarkus.runtime.config.DiatomRuntimeConfig;
import jakarta.ws.rs.core.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link MonitorResource} 测试（纯 JUnit：真实 core 组件 + 隔离临时注册表）。
 *
 * <p>gateway 模式：验证登录、status（含注册 worker）、i18n、静态页/JS 从 diatom-core
 * classpath 提供、workspace 拓扑/任务、config 校验、rules（无 DB → 500 降级）。</p>
 */
public class MonitorResourceTest {

    private Path tempHome;
    private String previousJarDir;
    private DiatomRuntimeContext context;
    private MonitorResource resource;

    @Before
    public void setUp() throws Exception {
        tempHome = Files.createTempDirectory("diatom-monitor-test");
        previousJarDir = System.getProperty("diatom.jar.dir");

        DiatomRuntimeConfig config = TestConfigFactory.from(Map.of("diatom.mode", "gateway"));
        DiatomComponents components = new DiatomComponents(config);
        context = new DiatomRuntimeContext(components);
        // 在 config 构建后设置：隔离 FileSystemWorkerRegistry 落盘注册文件到临时目录
        System.setProperty("diatom.jar.dir", tempHome.toString());
        resource = new MonitorResource(context, config);
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.kernel().stop();
        }
        if (previousJarDir != null) {
            System.setProperty("diatom.jar.dir", previousJarDir);
        } else {
            System.clearProperty("diatom.jar.dir");
        }
        if (tempHome != null) {
            try {
                Files.walk(tempHome)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    @Test
    public void loginReturnsToken() {
        Response res = resource.login(Map.of("username", "admin", "password", "admin"));
        assertEquals(200, res.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getEntity();
        assertNotNull("token should be returned", body.get("token"));
        assertEquals(86400, body.get("expiresIn"));
    }

    @Test
    public void statusReportsRegisteredWorker() {
        WorkerRegistry registry = context.kernel().workerRegistry();
        registry.register(new WorkerInfo("mon-worker", "127.0.0.1", 8082));

        Response res = resource.status(null);
        assertEquals(200, res.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getEntity();
        assertEquals(1, body.get("onlineWorkers"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> workers = (List<Map<String, Object>>) body.get("workers");
        assertEquals("mon-worker", workers.get(0).get("workerId"));
        assertFalse("authConfigured should be false", (Boolean) body.get("authConfigured"));
    }

    @Test
    public void i18nReturnsZhDictionary() {
        Response res = resource.i18n("zh");
        assertEquals(200, res.getStatus());
        assertTrue("i18n map should have entries", res.getEntity() instanceof Map);
    }

    @Test
    public void workspaceApiWorks() {
        Response topo = resource.workspaceTopology(null);
        assertEquals(200, topo.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> topoBody = (Map<String, Object>) topo.getEntity();
        assertEquals("gateway", ((Map<String, Object>) topoBody.get("gateway")).get("id"));

        Response tasks = resource.workspaceTasks(null, null, null);
        assertEquals(200, tasks.getStatus());
        assertNotNull(tasks.getEntity());
    }

    @Test
    public void configMissingWorkerIdReturns400() {
        assertEquals(400, resource.config(null, Map.of()).getStatus());
        assertEquals(400, resource.config(null, Map.of("workerId", "w1")).getStatus());
    }

    @Test
    public void rulesReturnsOk() {
        // 本环境 SQLite DB 自动初始化 → CommandRulesDao 可用，返回空规则列表（status=ok）
        Response res = resource.rules(null, "gateway");
        assertEquals(200, res.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getEntity();
        assertEquals("ok", body.get("status"));
        assertNotNull("rules list should be present", body.get("rules"));
    }

    @Test
    public void staticPagesServedFromClasspath() {
        Response dashboard = resource.index(null);
        assertEquals(200, dashboard.getStatus());
        assertTrue(((String) dashboard.getEntity()).contains("<!DOCTYPE"));

        Response login = resource.loginPage();
        assertEquals(200, login.getStatus());
        assertTrue(((String) login.getEntity()).contains("<!DOCTYPE"));

        Response echarts = resource.echartsJs();
        assertEquals(200, echarts.getStatus());
        assertTrue(((String) echarts.getEntity()).contains("echarts"));

        Response missing = resource.routingPage(null);
        assertEquals(200, missing.getStatus());
        assertTrue(((String) missing.getEntity()).contains("<!DOCTYPE"));
    }

    @Test
    public void redirectsReturn302() {
        assertEquals(302, resource.workspaceRedirect().getStatus());
        assertEquals(302, resource.routingRedirect().getStatus());
        assertEquals(302, resource.topologyRedirect().getStatus());
    }
}
