package com.github.obhen233.quarkus.runtime;

import com.github.obhen233.quarkus.runtime.config.DiatomRuntimeConfig;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

import java.util.Map;

/**
 * 测试用 {@link DiatomRuntimeConfig} 工厂：程序化构建 SmallRye Config Mapping。
 */
public final class TestConfigFactory {

    private TestConfigFactory() {
    }

    public static DiatomRuntimeConfig from(Map<String, String> properties) {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .addDefaultSources()
                .withDefaultValues(properties)
                .withMapping(DiatomRuntimeConfig.class)
                .build();
        return config.getConfigMapping(DiatomRuntimeConfig.class);
    }
}
