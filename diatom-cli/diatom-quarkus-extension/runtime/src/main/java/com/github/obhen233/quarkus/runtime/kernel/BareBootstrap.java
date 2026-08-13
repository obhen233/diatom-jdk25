package com.github.obhen233.quarkus.runtime.kernel;

import com.github.obhen233.core.agent.ReActAgent;
import org.jboss.logging.Logger;

/**
 * Standard（纯引擎）模式装配。
 *
 * <p>只装配 ReActAgent（AiHttpClient / ModelAdapter / ToolRegistry / SkillManager /
 * SystemPromptManager / ProjectIndexer / McpClientManager），<b>不起 HTTP 服务器</b> ——
 * 契合"进程内调用、不走 HTTP"。与 starter 的 {@code DiatomAutoConfiguration} 对应。
 */
public class BareBootstrap extends ModeBootstrap {

    private static final Logger LOGGER = Logger.getLogger(BareBootstrap.class);

    private ReActAgent reActAgent;

    public BareBootstrap(DiatomKernel kernel) {
        super(kernel);
    }

    @Override
    public void start() {
        try {
            this.reActAgent = buildReActAgent(kernel.getShared().appConfig.getModel(),
                    kernel.getShared().appConfig.getApiUrl());
            LOGGER.info("Standard mode: ReActAgent assembled (no HTTP server)");
        } catch (Exception e) {
            LOGGER.errorf("Failed to assemble ReActAgent: %s", e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (reActAgent != null) {
            try {
                reActAgent.shutdown();
            } catch (Exception e) {
                LOGGER.warnf("ReActAgent shutdown failed: %s", e.getMessage());
            }
            reActAgent = null;
        }
    }

    @Override
    public ReActAgent reActAgent() {
        return reActAgent;
    }
}
