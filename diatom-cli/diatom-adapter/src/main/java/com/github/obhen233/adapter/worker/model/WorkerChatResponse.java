package com.github.obhen233.adapter.worker.model;

import java.util.List;
import java.util.Map;

/**
 * Wire protocol DTO for {@code POST /worker/v1/chat} response.
 * Aligned with diatom-core WorkerChatResponse format.
 *
 * @param status    completion status
 * @param taskId    the task id
 * @param response  the agent's textual response
 * @param fileDiffs file change records (may be null)
 */
public record WorkerChatResponse(
        String status,
        String taskId,
        String response,
        List<Map<String, Object>> fileDiffs) {

    public WorkerChatResponse(String status, String taskId, String response) {
        this(status, taskId, response, null);
    }
}
