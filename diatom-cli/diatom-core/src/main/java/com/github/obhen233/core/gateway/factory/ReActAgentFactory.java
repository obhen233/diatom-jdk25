package com.github.obhen233.core.gateway.factory;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.tool.ToolRegistry;

/**
 * ReActAgent 工厂
 * Worker 模式下为每个请求创建独立的 Agent 实例
 */
public class ReActAgentFactory {

    private final AppConfig config;
    private final ToolRegistry toolRegistry;
    private final AiHttpClient httpClient;
    private final ModelAdapter modelAdapter;

    public ReActAgentFactory(AppConfig config, ToolRegistry toolRegistry,
                             AiHttpClient httpClient, ModelAdapter modelAdapter) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.httpClient = httpClient;
        this.modelAdapter = modelAdapter;
    }

    /**
     * 创建新的 Agent 实例（每个请求调用一次）
     */
    public ReActAgent createAgent() {
        return new ReActAgent(httpClient, modelAdapter, toolRegistry,
                null, null, null, null, config.getModel(), config.getApiUrl());
    }

    /**
     * 创建 Gateway 专用的轻量 Agent（步骤少，无工具链）
     */
    public ReActAgent createGatewayAgent() {
        return new ReActAgent(httpClient, modelAdapter, toolRegistry,
                null, null, null, null, config.getModel(), config.getApiUrl());
    }
}
