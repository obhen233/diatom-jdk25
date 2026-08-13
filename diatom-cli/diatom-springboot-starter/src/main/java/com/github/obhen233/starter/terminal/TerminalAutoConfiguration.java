package com.github.obhen233.starter.terminal;

import com.github.obhen233.core.agent.ReActAgent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.config.annotation.EnableWebSocket;

/**
 * Auto-configuration for the IDE WebSocket terminal.
 * Activated when diatom.ide.terminal-enabled=true.
 * Requires WebSocket and ReActAgent on the classpath.
 */
@Configuration
@ConditionalOnProperty(prefix = "diatom.ide", name = "terminal-enabled", havingValue = "true")
@ConditionalOnClass({ EnableWebSocket.class, ReActAgent.class })
@Import(TerminalWebSocketConfig.class)
public class TerminalAutoConfiguration {

    @Bean
    public TerminalSessionController terminalSessionController(TerminalWebSocketHandler handler) {
        return new TerminalSessionController(handler);
    }
}
