package com.github.obhen233.adapter.worker.model;

import java.util.List;
import java.util.Map;

/**
 * Wire protocol DTO for {@code POST /worker/v1/chat}.
 * Aligned with diatom-core ChatRequestPayload + additional fields.
 *
 * @param taskId              the task id
 * @param message             the user message
 * @param workspacePath       the workspace directory
 * @param conversationHistory prior chat messages (maps with role/content)
 * @param syncStrategy        sync strategy
 * @param gatewayUrl          gateway address
 * @param metadata            additional metadata
 */
public record ChatRequest(
        String taskId,
        String message,
        String workspacePath,
        List<Map<String, String>> conversationHistory,
        String syncStrategy,
        String gatewayUrl,
        Map<String, String> metadata) {}
