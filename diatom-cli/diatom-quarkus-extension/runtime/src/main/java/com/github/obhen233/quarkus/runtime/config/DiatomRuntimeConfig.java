package com.github.obhen233.quarkus.runtime.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Optional;

/**
 * Diatom Quarkus 运行时配置（{@code diatom.*}）。
 *
 * <p>对应 Spring Boot starter 的 {@code DiatomProperties} + 各模式 Properties，
 * 覆盖运行模式与 Gateway/Worker/Adapter/Monitor/Terminal/Cloud 全部参数。
 * 装配层通过 {@code @ConfigMapping} 在构建期完成校验。
 *
 * <pre>
 * diatom.mode=standard                       # standard|gateway|worker|child|adapter
 * diatom.app.workspace-dir=${user.dir}
 * diatom.api.key=sk-xxx
 * diatom.api.base-url=https://api.example.com
 * diatom.gateway.standalone-port=0           # &gt;0 时在同一进程内启动 core GatewayHttpServer（双端口）
 * diatom.worker.standalone-port=0            # &gt;0 时在同一进程内启动 core WorkerHttpServer（双端口）
 * </pre>
 */
@ConfigMapping(prefix = "diatom")
public interface DiatomRuntimeConfig {

    /**
     * 运行模式：{@code standard}（默认，纯引擎，不起 HTTP）/ {@code gateway} / {@code worker} /
     * {@code child}（子 Gateway）/ {@code adapter}（AgentAdapter 驱动）。
     */
    @WithDefault("standard")
    String mode();

    /** 应用级配置。 */
    App app();

    /** 数据库配置（映射为 {@code diatom.database.*} 系统属性，交给 core HibernateConfig）。 */
    Database database();

    /** 插件搜索路径配置。 */
    Plugin plugin();

    /** API 配置（复用 diatom-core 的 LLM 接入）。 */
    Api api();

    /** Gateway 模式配置。 */
    Gateway gateway();

    /** Worker 模式配置。 */
    Worker worker();

    /** Adapter 模式配置。 */
    Adapter adapter();

    /** Monitor 管理 UI 配置。 */
    Monitor monitor();

    /** IDE Terminal 配置。 */
    Terminal terminal();

    /** Cloud 注册中心配置（SmallRye Stork 路线）。 */
    Cloud cloud();

    interface App {
        /** 工作区目录（缺省用 {@code AppConfig.getWorkspaceDir()}）。 */
        Optional<String> workspaceDir();

        /** 自定义 User-Agent。 */
        Optional<String> userAgent();

        /** Agent 语言（en/zh）。 */
        @WithDefault("zh")
        String language();
    }

    interface Database {
        Optional<String> url();

        Optional<String> username();

        Optional<String> password();

        Optional<Integer> poolSize();

        Optional<String> dialect();

        Optional<String> driver();
    }

    interface Plugin {
        /** 额外插件搜索目录（逗号分隔）。 */
        Optional<List<String>> paths();
    }

    interface Api {
        Optional<String> key();

        @WithDefault("https://api.openai.com")
        String baseUrl();

        /** API endpoint 路径（如 /anthropic）。 */
        Optional<String> endpoint();

        @WithDefault("gpt-4")
        String model();

        /** auto / openai / anthropic。 */
        @WithDefault("auto")
        String format();

        @WithDefault("8192")
        int maxTokens();

        @WithDefault("200000")
        int contextWindow();
    }

    interface Gateway {
        /** Gateway 自身 URL，Worker 回连地址。 */
        Optional<String> url();

        /** 子 Gateway 节点标记（tier=gateway-proxy）。 */
        @WithDefault("false")
        boolean child();

        /** 子 Gateway 外部访问端口。 */
        Optional<Integer> externalPort();

        /** Gateway 对外发布端口（注册上报用）。 */
        @WithDefault("8080")
        int port();

        /** Gateway 实例 ID。 */
        Optional<String> instanceId();

        /** 队列模式（202 异步）。 */
        @WithDefault("false")
        boolean queueEnabled();

        /** HA 集群模式。 */
        @WithDefault("false")
        boolean haEnabled();

        /** &gt;0 时在同一进程内启动 core GatewayHttpServer 于独立端口（双端口应用）。 */
        @WithDefault("0")
        int standalonePort();
    }

    interface Worker {
        /** Worker 实例 ID，注册时上报。 */
        Optional<String> instanceId();

        /** 注册/心跳的 Gateway URL。 */
        Optional<String> gatewayUrl();

        /** 对外发布端口（注册上报用，缺省 = Quarkus HTTP 端口）。 */
        @WithDefault("8080")
        int port();

        /** 模型覆盖（缺省用 api.model）。 */
        Optional<String> model();

        /** 注册分组。 */
        Optional<String> group();

        /** 最大并发数。 */
        @WithDefault("1")
        int maxConcurrency();

        /** 外部可访问 host（注册上报，缺省探测本机 IP）。 */
        Optional<String> externalHost();

        /** 外部可访问端口（注册上报，缺省 = port）。 */
        Optional<Integer> externalPort();

        /** &gt;0 时在同一进程内启动 core WorkerHttpServer 于独立端口（双端口应用）。 */
        @WithDefault("0")
        int standalonePort();
    }

    interface Adapter {
        /** Adapter 模式是否启用。 */
        @WithDefault("true")
        boolean enabled();

        /** Adapter 工作区目录。 */
        Optional<String> workspaceDir();
    }

    interface Monitor {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("monitor")
        String prefix();

        Optional<String> username();

        Optional<String> password();

        /** Session Token 过期时间（秒，默认 86400 = 24 小时）。 */
        @WithDefault("86400")
        int tokenExpireSeconds();
    }

    interface Terminal {
        @WithDefault("false")
        boolean enabled();

        @WithDefault("/ide/terminal")
        String path();
    }

    interface Cloud {
        /** none / stork / consul / eureka / nacos(TODO)。 */
        @WithDefault("none")
        String type();

        /** 注册中心地址（consul / eureka，默认 localhost）。 */
        @WithDefault("localhost")
        String host();

        /** 注册中心端口（consul 8500 / eureka 8761，默认 8500）。 */
        @WithDefault("8500")
        int port();

        /** 本地实例自注册服务名（worker 自注册 + gateway 发现，默认 diatom）。 */
        @WithDefault("diatom")
        String serviceName();

        /** Gateway 自注册服务名（拓扑识别 + 发现时跳过 gateway 自身，默认 diatom-gateway）。 */
        @WithDefault("diatom-gateway")
        String gatewayServiceFilter();

        /** Worker 发现过滤（预留；v1 单服务名发现，留作扩展）。 */
        Optional<String> serviceFilter();

        /** Stork 发现刷新间隔（毫秒）。 */
        @WithDefault("30000")
        long refreshIntervalMs();

        /** 云发现 worker 默认分组。 */
        @WithDefault("cloud")
        String workerGroup();

        /** 云发现 worker 默认 model（缺省用 api.model）。 */
        Optional<String> defaultModel();
    }
}
