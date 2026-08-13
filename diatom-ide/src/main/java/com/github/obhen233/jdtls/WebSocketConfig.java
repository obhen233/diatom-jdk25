package com.github.obhen233.jdtls;

import com.github.obhen233.compiler.controller.TerminalWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final JdtLsSocketHandler socketHandler;
    private final TerminalWebSocketHandler terminalWebSocketHandler;

    public WebSocketConfig(JdtLsSocketHandler socketHandler, TerminalWebSocketHandler terminalWebSocketHandler) {
        this.socketHandler = socketHandler;
        this.terminalWebSocketHandler = terminalWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socketHandler, "/java-lsp")
                .setAllowedOrigins("http://localhost:5173", "http://localhost:8080", "http://127.0.0.1:5173", "http://127.0.0.1:8080");
        registry.addHandler(terminalWebSocketHandler, "/terminal-ws")
                .setAllowedOrigins("*")
                .addInterceptors(terminalWebSocketHandler);
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(1024 * 1024);
        container.setMaxBinaryMessageBufferSize(1024 * 1024);
        return container;
    }
}
