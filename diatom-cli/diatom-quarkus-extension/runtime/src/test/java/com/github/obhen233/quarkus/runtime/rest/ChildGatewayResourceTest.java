package com.github.obhen233.quarkus.runtime.rest;

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
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * {@link ChildGatewayResource} 测试（纯 JUnit：真实 core 组件 + 隔离临时注册表）。
 *
 * <p>child 模式：ChildBootstrap 继承 GatewayBootstrap，装配 GatewayAgent/
 * WorkerRegistry。验证缺 body / 缺 message 的 400 分支（均在 LLM 分析前）。
 * 完整转发链依赖 LLM 分类 + 真实下挂 worker，不在此触发。</p>
 */
public class ChildGatewayResourceTest {

    private Path tempHome;
    private String previousJarDir;
    private DiatomRuntimeContext context;
    private ChildGatewayResource resource;

    @Before
    public void setUp() throws Exception {
        tempHome = Files.createTempDirectory("diatom-child-test");
        previousJarDir = System.getProperty("diatom.jar.dir");

        DiatomRuntimeConfig config = TestConfigFactory.from(Map.of("diatom.mode", "child"));
        DiatomComponents components = new DiatomComponents(config);
        context = new DiatomRuntimeContext(components);
        // 在 config 构建后设置：隔离 FileSystemWorkerRegistry 落盘注册文件到临时目录
        // （diatom.jar.dir 是 diatom 前缀下的未知属性，SmallRye 校验会拒绝，故必须后置）
        System.setProperty("diatom.jar.dir", tempHome.toString());
        resource = new ChildGatewayResource(context, config);
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
    public void chatMissingBodyReturns400() {
        assertEquals(400, resource.handleWorkerChat(null).getStatus());
    }

    @Test
    public void chatMissingMessageReturns400() {
        assertEquals(400, resource.handleWorkerChat(Map.of()).getStatus());
        assertEquals(400, resource.handleWorkerChat(Map.of("taskId", "t1")).getStatus());
    }
}
