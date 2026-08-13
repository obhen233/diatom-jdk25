package com.github.obhen233.adapter.worker.model;

/**
 * SSE event model, aligned with diatom-core SseEvent format.
 *
 * <p>Serialized as JSON and sent as {@code data: {...}} lines in SSE responses.</p>
 *
 * @param type      event type ("start"/"token"/"complete"/"error"/"result")
 * @param taskId    the task id
 * @param content   event content (token text or error message)
 * @param fileDiffs file change records
 * @param result    result payload
 */
public record SseEvent(
        String type,
        String taskId,
        String content,
        Object fileDiffs,
        Object result) {

    public static SseEvent start(String taskId) {
        return new SseEvent("start", taskId, null, null, null);
    }

    public static SseEvent token(String content, Object fileDiffs) {
        return new SseEvent("token", null, content, fileDiffs, null);
    }

    public static SseEvent complete(String taskId) {
        return new SseEvent("complete", taskId, null, null, null);
    }

    public static SseEvent error(String content) {
        return new SseEvent("error", null, content, null, null);
    }

    public static SseEvent resultEvent(String taskId) {
        return new SseEvent("result", taskId, null, null, null);
    }
}
