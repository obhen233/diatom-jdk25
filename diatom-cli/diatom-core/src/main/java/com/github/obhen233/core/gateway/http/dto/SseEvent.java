package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.obhen233.util.JsonUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SseEvent {
    public String type;
    public String taskId;
    public String content;
    public Object fileDiffs;
    public String worker;
    public String mode;
    public Object result;

    public SseEvent() {}

    public SseEvent(String type, String taskId) {
        this.type = type;
        this.taskId = taskId;
    }

    public static SseEvent start(String taskId) {
        return new SseEvent("start", taskId);
    }

    public static SseEvent token(String content, Object fileDiffs) {
        SseEvent event = new SseEvent("token", null);
        event.content = content;
        event.fileDiffs = fileDiffs;
        return event;
    }

    public static SseEvent complete(String taskId) {
        return new SseEvent("complete", taskId);
    }

    public static SseEvent error(String content) {
        SseEvent event = new SseEvent("error", null);
        event.content = content;
        return event;
    }

    public static SseEvent resultEvent(String taskId) {
        return new SseEvent("result", taskId);
    }

    public static SseEvent routedEvent(String taskId) {
        return new SseEvent("routed", taskId);
    }

    public void writeTo(OutputStream os) throws IOException {
        String line = "data: " + JsonUtils.toJson(this) + "\n\n";
        os.write(line.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
}
