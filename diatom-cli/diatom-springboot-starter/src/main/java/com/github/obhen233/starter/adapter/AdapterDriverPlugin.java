package com.github.obhen233.starter.adapter;

import java.util.Map;

/**
 * Adapter 模式驱动插件 SPI。
 *
 * <p>当 {@code diatam.mode=adapter} 时，springboot-starter 通过 {@link java.util.ServiceLoader}
 * 发现并加载所有 {@code AdapterDriverPlugin} 实现。
 *
 * <p>实现类需要在 {@code META-INF/services/com.github.obhen233.starter.adapter.AdapterDriverPlugin}
 * 中注册，并将 JAR 放入 plugins/ 目录或配置到 {@code diatam.plugin.paths}。</p>
 */
public interface AdapterDriverPlugin {

    /** 驱动类型标识，如 "claude-code", "cursor", "custom" */
    String getDriverType();

    /** 驱动名称，用于日志和监控 */
    String getDriverName();

    /** 初始化驱动 */
    void initialize(AdapterDriverConfig config);

    /** 处理适配请求 */
    AdapterResponse handleRequest(AdapterRequest request);

    /** 驱动是否健康 */
    boolean isHealthy();

    /** 数据模型：驱动配置 */
    class AdapterDriverConfig {
        private int port;
        private String bindAddress;
        private Map<String, String> options;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getBindAddress() { return bindAddress; }
        public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }
        public Map<String, String> getOptions() { return options; }
        public void setOptions(Map<String, String> options) { this.options = options; }
    }

    /** 数据模型：适配请求 */
    class AdapterRequest {
        private String agentId;
        private String message;
        private Map<String, String> metadata;

        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
    }

    /** 数据模型：适配响应 */
    class AdapterResponse {
        private boolean success;
        private String content;
        private String error;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public static AdapterResponse ok(String content) {
            AdapterResponse resp = new AdapterResponse();
            resp.success = true;
            resp.content = content;
            return resp;
        }

        public static AdapterResponse fail(String error) {
            AdapterResponse resp = new AdapterResponse();
            resp.success = false;
            resp.error = error;
            return resp;
        }
    }
}
