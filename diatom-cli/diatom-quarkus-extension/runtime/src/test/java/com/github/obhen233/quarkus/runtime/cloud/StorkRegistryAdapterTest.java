package com.github.obhen233.quarkus.runtime.cloud;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * {@link StorkRegistryAdapter} 降级契约测试（纯 JUnit，无真实注册中心/provider jar）。
 *
 * <p>验证 adapter 的<b>优雅降级</b>：cloud.type=none 时全 no-op；consul/eureka 但 provider jar 缺失或
 * 注册中心不可达时，发现返回空、注册/注销不抛异常、不阻塞。真实 consul/eureka 需要 live server，
 * 属端到端验证（后续可选）。</p>
 */
public class StorkRegistryAdapterTest {

    private static CloudDiscoveryConfig config(String type) {
        return new CloudDiscoveryConfig(type, "localhost", 8500, "diatom", "diatom-gateway",
                "", 30000, "cloud", "gpt-4");
    }

    @Test
    public void noneTypeIsNoop() {
        StorkRegistryAdapter adapter = new StorkRegistryAdapter(config("none"));
        adapter.init();
        assertTrue("disabled 时发现应为空", adapter.discover("diatom").isEmpty());
        adapter.registerInstance("diatom", "w1", "127.0.0.1", 8081); // no-op
        adapter.deregisterInstance("diatom", "w1", "127.0.0.1", 8081); // no-op
        adapter.close();
    }

    @Test
    public void consulWithoutServerDegradesGracefully() {
        StorkRegistryAdapter adapter = new StorkRegistryAdapter(config("consul"));
        adapter.init();
        // 无 consul provider / 注册中心不可达 → 发现返回空，注册/注销不抛异常
        assertTrue(adapter.discover("diatom").isEmpty());
        adapter.registerInstance("diatom", "w1", "127.0.0.1", 8081);
        adapter.deregisterInstance("diatom", "w1", "127.0.0.1", 8081);
        adapter.close();
    }
}
