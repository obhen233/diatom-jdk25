package com.github.obhen233.starter.terminal;

import com.github.obhen233.core.agent.ReActAgent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket configuration for the IDE terminal.
 * Registers the terminal WebSocket handler at the configured path.
 */
@Configuration
@EnableWebSocket
public class TerminalWebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalHandler;
    private final String terminalPath;

    public TerminalWebSocketConfig(TerminalWebSocketHandler terminalHandler, String terminalPath) {
        this.terminalHandler = terminalHandler;
        this.terminalPath = terminalPath;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalHandler, terminalPath)
                .setAllowedOrigins("*");
    }

    /**
     * Factory bean for TerminalWebSocketHandler.
     * Uses the configured ReActAgent as a template for new terminal sessions.
     */
    @Configuration
    public static class HandlerFactory {

        @Bean
        public TerminalWebSocketHandler terminalWebSocketHandler(ReActAgent agent) {
            return new TerminalWebSocketHandler(agent);
        }

        @Bean
        public String terminalPath(com.github.obhen233.starter.DiatomProperties properties) {
            return properties.getIde().getTerminalPath();
        }
    }
}
