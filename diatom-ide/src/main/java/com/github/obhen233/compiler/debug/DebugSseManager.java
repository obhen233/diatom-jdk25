package com.github.obhen233.compiler.debug;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.function.BooleanSupplier;

/**
 * 调试会话 SSE 流管理。
 * 统一封装 SseEmitter 的创建、事件发送、心跳保活与结束处理，
 * 消除 {@link DebugController} 中三个端点（start/reconnect/attach）重复的样板代码。
 */
@Component
public class DebugSseManager {

    /** SSE 流默认超时：10 分钟 */
    private static final long DEFAULT_TIMEOUT = 600_000L;

    /** 创建带默认超时的 SSE 发射器。 */
    public SseEmitter createEmitter() {
        return new SseEmitter(DEFAULT_TIMEOUT);
    }

    /** 创建无超时（长连接）的 SSE 发射器。 */
    public SseEmitter createInfiniteEmitter() {
        return new SseEmitter(0L);
    }

    /**
     * 发送一个 SSE 事件。客户端断开或发射器已完成后静默忽略失败。
     */
    public void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception ignored) {
            // Client disconnected or emitter completed
        }
    }

    /**
     * 发送 error 事件并追加 done 事件，标志会话以失败结束。
     */
    public void sendError(SseEmitter emitter, String msg) {
        sendEvent(emitter, "error", msg);
        sendEvent(emitter, "done", "{\"exitCode\":-1}");
    }

    /**
     * 创建一个将 SSE 事件转发给指定发射器的回调；
     * 收到 {@code sessionEnd} 事件时自动完成发射器。
     */
    public DebugService.SseEventCallback createEventCallback(SseEmitter emitter) {
        return (event, data) -> {
            sendEvent(emitter, event, data);
            if ("sessionEnd".equals(event)) {
                emitter.complete();
            }
        };
    }

    /**
     * 在当前线程上保活 SSE 发射器：每 1 秒发送一次命名心跳事件，
     * 唤醒前端 reader.read() 以便冲刷半缓冲事件。当 {@code running}
     * 返回 false（会话结束）或线程被中断时停止。
     */
    public void keepAliveWhileRunning(SseEmitter emitter, BooleanSupplier running) {
        while (running.getAsBoolean()) {
            try {
                emitter.send(SseEmitter.event().name("ping").data(""));
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // emitter.send may fail if connection is closed; just continue
            }
        }
    }

    /** 转义 JSON 字符串中的反斜杠与双引号。 */
    public String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
