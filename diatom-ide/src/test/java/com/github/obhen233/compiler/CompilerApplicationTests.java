package com.github.obhen233.compiler;

import com.github.obhen233.compiler.auth.AuthProvider;
import com.github.obhen233.compiler.controller.AiChatWebSocketHandler;
import com.github.obhen233.compiler.controller.TerminalWebSocketHandler;
import com.github.obhen233.compiler.debug.DebugSseManager;
import com.github.obhen233.compiler.service.JavaCompileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 冒烟测试：验证完整 Spring 上下文可启动，且关键 Bean（含重构后新加的
 * {@link AiChatWebSocketHandler} 与 {@link DebugSseManager}）都已装配。
 * <p>
 * 使用 {@code RANDOM_PORT} 启动真实 Tomcat —— WebSocketConfig 的
 * {@code ServletServerContainerFactoryBean} 需要真实的 ServletContext（MOCK 环境不提供）。
 * 若某个 {@code @Autowired} 注入失败，本测试会立即失败，从而在打包/发布前拦截装配回归。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-ide.db",
        "spring.jpa.hibernate.ddl-auto=update"
})
class CompilerApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertNotNull(context, "Spring context should load");
    }

    @Test
    void keyBeansAreWired() {
        assertNotNull(context.getBean(TerminalWebSocketHandler.class), "TerminalWebSocketHandler bean");
        assertNotNull(context.getBean(AiChatWebSocketHandler.class), "AiChatWebSocketHandler bean");
        assertNotNull(context.getBean(DebugSseManager.class), "DebugSseManager bean");
        assertNotNull(context.getBean(JavaCompileService.class), "JavaCompileService bean");
        assertNotNull(context.getBean(AuthProvider.class), "AuthProvider bean");
    }
}
