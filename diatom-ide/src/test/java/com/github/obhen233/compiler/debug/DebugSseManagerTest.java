package com.github.obhen233.compiler.debug;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * {@link DebugSseManager} 单元测试 —— 覆盖发射器创建、JSON 转义，
 * 以及 {@code sessionEnd} 事件到达时自动完成发射器的回调行为。
 */
class DebugSseManagerTest {

    private final DebugSseManager manager = new DebugSseManager();

    @Test
    void createEmitter_hasTenMinuteTimeout() {
        assertEquals(600_000L, manager.createEmitter().getTimeout());
    }

    @Test
    void createInfiniteEmitter_hasNoTimeout() {
        assertEquals(0L, manager.createInfiniteEmitter().getTimeout());
    }

    @Test
    void escapeJson_escapesBackslashesAndQuotes() {
        assertEquals("a\\\\b\\\"c", manager.escapeJson("a\\b\"c"));
    }

    @Test
    void createEventCallback_completesEmitterOnSessionEnd() {
        SseEmitter emitter = spy(new SseEmitter(600_000L));
        DebugService.SseEventCallback callback = manager.createEventCallback(emitter);
        // 普通事件不触发 complete
        callback.onEvent("line", "some output");
        verify(emitter, org.mockito.Mockito.never()).complete();
        // sessionEnd 触发 complete
        callback.onEvent("sessionEnd", "done");
        verify(emitter).complete();
    }

    @Test
    void sendError_doesNotThrowWhenEmitterUninitialized() {
        // 无客户端连接时 SseEmitter.send() 会抛异常，管理器应静默吞掉。
        SseEmitter emitter = new SseEmitter(600_000L);
        manager.sendError(emitter, "boom");
    }
}
