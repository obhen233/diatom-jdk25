package com.github.obhen233.quarkus.runtime.cloud;

import java.util.Map;

/**
 * 从注册中心发现的服务实例快照（record，不可变）。
 *
 * <p>传输无关：{@link StorkRegistryAdapter} 把 SmallRye Stork 的 {@code ServiceInstance}
 * 映射为 {@link DiscoveredInstance}，下游（{@link StorkWorkerRegistry}）不直接依赖 Stork 类型。
 */
public record DiscoveredInstance(String host, int port, boolean secure, Map<String, String> metadata) {

    /** 读取 metadata（diatom.* 键），缺键返回 null。 */
    public String metadata(String key) {
        if (metadata == null) return null;
        return metadata.get(key);
    }

    /** 读取 metadata（diatom.* 键），缺键/空返回默认值。 */
    public String metadata(String key, String defaultValue) {
        String v = metadata(key);
        return v != null && !v.isEmpty() ? v : defaultValue;
    }
}
