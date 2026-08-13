package com.github.obhen233.quarkus.runtime.rest;

import com.github.obhen233.quarkus.runtime.TestConfigFactory;
import com.github.obhen233.quarkus.runtime.components.DiatomComponents;
import com.github.obhen233.quarkus.runtime.components.DiatomRuntimeContext;
import com.github.obhen233.quarkus.runtime.config.DiatomRuntimeConfig;
import com.github.obhen233.quarkus.runtime.rest.dto.LockRequest;
import com.github.obhen233.quarkus.runtime.rest.dto.MetricsPayload;
import com.github.obhen233.quarkus.runtime.rest.dto.WorkerRegisterRequest;
import jakarta.ws.rs.core.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link GatewayResource} 测试（纯 JUnit：真实 core 组件 + 隔离的临时文件注册表）。
 *
 * <p>gateway 模式：验证 Worker 注册/心跳/列表/注销、健康检查、分布式锁、
 * 管理端点（/api/diatom/*）及入参校验。聊天路由依赖 LLM 分类，
 * 纯 JUnit 内不触发真实模型调用，仅验证缺 message 的 400 分支。</p>
 */
public class GatewayResourceTest {

    private Path tempHome;
    private String previousJarDir;
    private DiatomRuntimeContext context;
    private GatewayResource resource;

    @Before
    public void setUp() throws Exception {
        tempHome = Files.createTempDirectory("diatom-gateway-test");
        previousJarDir = System.getProperty("diatom.jar.dir");

        DiatomRuntimeConfig config = TestConfigFactory.from(Map.of("diatom.mode", "gateway"));
        DiatomComponents components = new DiatomComponents(config);
        context = new DiatomRuntimeContext(components);
        // 在 config 构建后设置：隔离 FileSystemWorkerRegistry 落盘注册文件到临时目录
        // （diatom.jar.dir 是 diatom 前缀下的未知属性，SmallRye 校验会拒绝，故必须后置）
        System.setProperty("diatom.jar.dir", tempHome.toString());
        resource = new GatewayResource(context, config);
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

    private static WorkerRegisterRequest sampleWorker(String id) {
        return new WorkerRegisterRequest(id, "127.0.0.1", 8081, "gpt-4", "default",
                "/tmp/ws", null, 0.0, 0, 2, null, false);
    }

    @Test
    public void registerListGetHeartbeatDeregisterWorkflow() throws InterruptedException {
        assertEquals(200, resource.registerWorker(sampleWorker("w1")).getStatus());

        // 重复注册（已在线的 workerId）→ 409
        assertEquals(409, resource.registerWorker(sampleWorker("w1")).getStatus());

        Response list = resource.listWorkers();
        assertEquals(200, list.getStatus());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> workers = (List<Map<String, Object>>) list.getEntity();
        assertEquals(1, workers.size());
        assertEquals("w1", workers.get(0).get("workerId"));

        assertEquals(200, resource.getWorker("w1").getStatus());

        assertEquals(200, resource.heartbeat("w1", new MetricsPayload(0.5, 1, false)).getStatus());

        assertEquals(200, resource.deregisterWorker("w1").getStatus());
        // 核心 FileSystemWorkerRegistry 的 WatchService 会异步处理文件事件（可能短暂重放
        // 注册内容再删除），故对注销结果做有界轮询而非立即断言。
        awaitWorkerAbsent(resource, "w1");
    }

    /** 有界轮询：等待 WatchService 消费完 deregister 的 DELETE 事件（最多 5s）。 */
    private static void awaitWorkerAbsent(GatewayResource resource, String workerId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (resource.getWorker(workerId).getStatus() == 404) {
                return;
            }
            Thread.sleep(50);
        }
        assertEquals(404, resource.getWorker(workerId).getStatus());
    }

    @Test
    public void registerMissingWorkerIdReturns400() {
        WorkerRegisterRequest req = new WorkerRegisterRequest("", "127.0.0.1", 8081,
                "gpt-4", null, null, null, 0.0, 0, 0, null, false);
        assertEquals(400, resource.registerWorker(req).getStatus());
    }

    @Test
    public void healthReportsUp() {
        Response res = resource.health();
        assertEquals(200, res.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getEntity();
        assertEquals("UP", body.get("status"));
    }

    @Test
    public void lockAcquireStatusRelease() {
        LockRequest acquire = new LockRequest("res-1", "worker-x", "WRITE", 30000, 1000, null, 0);
        Response res = resource.acquireLock(acquire);
        assertEquals(200, res.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getEntity();
        assertEquals(true, body.get("success"));
        String token = (String) body.get("token");
        assertNotNull(token);

        Response status = resource.lockStatus("res-1");
        assertEquals(200, status.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> statusBody = (Map<String, Object>) status.getEntity();
        assertTrue("Lock should be held", !((List<?>) statusBody.get("locks")).isEmpty());

        LockRequest release = new LockRequest("res-1", "worker-x", null, 0, 0, token, 0);
        assertEquals(200, resource.releaseLock(release).getStatus());
    }

    @Test
    public void chatMissingMessageReturns400() {
        assertEquals(400, resource.chat(Map.of()).getStatus());
        assertEquals(400, resource.chat(null).getStatus());
    }

    @Test
    public void apiEndpointsWork() {
        assertEquals(200, resource.registerWorker(sampleWorker("api-worker")).getStatus());
        assertEquals(200, resource.apiListWorkers().getStatus());
        assertEquals(200, resource.apiGetWorker("api-worker").getStatus());
        assertEquals(404, resource.apiGetWorker("nope").getStatus());
        assertEquals(200, resource.apiHealth().getStatus());
    }
}
