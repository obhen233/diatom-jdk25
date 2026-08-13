package com.github.obhen233.starter.mode;

/**
 * Diatom 运行模式枚举。
 *
 * <p>通过 {@code diatam.mode} 配置项指定，决定 springboot-starter 的启动行为：</p>
 * <ul>
 *   <li>{@link #STANDARD} — 单机 Agent 模式（默认）。创建 ReActAgent + 本地工具，不暴露网络端口。</li>
 *   <li>{@link #GATEWAY} — Embedded Gateway 模式。启动 HTTP Server，接收 Worker 注册，LLM 路由分发。
 *       支持子类型指定注册中心：{@code gateway:nacos}、{@code gateway:eureka}、{@code gateway:consul}。</li>
 *   <li>{@link #WORKER} — Worker 节点模式。注册到远程 Gateway，通过 {@code @RestController} 接收并执行任务。</li>
 *   <li>{@link #ADAPTER} — 适配器节点模式。需要 {@code AdapterDriverPlugin} SPI 驱动，桥接非 diatom AI Agent。</li>
 *   <li>{@link #API} — API 客户端模式。通过 HTTP 调用远程 Gateway 的 API，不启动本地 Agent。</li>
 * </ul>
 *
 * <p>子类型由 {@link ModeUtils#parseSubType(String)} 解析。</p>
 */
public enum DiatomMode {

    STANDARD("standard"),
    GATEWAY("gateway"),
    WORKER("worker"),
    ADAPTER("adapter"),
    API("api");

    private final String value;

    DiatomMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 从字符串解析模式（忽略子类型）。
     * 例如 "gateway:nacos" → GATEWAY，"standard" → STANDARD。
     */
    public static DiatomMode fromValue(String modeStr) {
        if (modeStr == null || modeStr.isEmpty()) return STANDARD;
        String base = modeStr.contains(":") ? modeStr.split(":", 2)[0] : modeStr;
        for (DiatomMode mode : values()) {
            if (mode.value.equals(base)) {
                return mode;
            }
        }
        return STANDARD;
    }
}
