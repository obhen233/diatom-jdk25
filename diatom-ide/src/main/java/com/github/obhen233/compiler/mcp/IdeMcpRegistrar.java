package com.github.obhen233.compiler.mcp;

import com.github.obhen233.core.mcp.McpClientManager;
import com.github.obhen233.core.mcp.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Registers all IDE MCP servers with diatom-core's McpClientManager
 * after the application is fully ready.
 *
 * External MCP server loading from config files (~/.diatom/mcpservers/)
 * is handled by DiatomAutoConfiguration.McpConfigInitializer.
 */
@Component
public class IdeMcpRegistrar implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger logger = LoggerFactory.getLogger(IdeMcpRegistrar.class);

    @Autowired(required = false)
    private McpClientManager mcpManager;

    @Autowired(required = false)
    private List<McpServer> ideMcpServers;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (mcpManager == null) {
            logger.warn("McpClientManager not available, skipping MCP server registration");
            return;
        }

        if (ideMcpServers == null || ideMcpServers.isEmpty()) {
            logger.info("No IDE MCP servers to register");
            return;
        }

        for (McpServer server : ideMcpServers) {
            try {
                mcpManager.registerServer(server);
                logger.info("Registered IDE MCP server: {} - {}", server.getName(), server.getDescription());
            } catch (Exception e) {
                logger.error("Failed to register MCP server: {}", server.getName(), e);
            }
        }
        logger.info("IDE MCP registration complete: {} servers registered", ideMcpServers.size());
    }
}
