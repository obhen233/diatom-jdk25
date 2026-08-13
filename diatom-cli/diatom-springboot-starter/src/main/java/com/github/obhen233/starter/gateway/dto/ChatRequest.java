package com.github.obhen233.starter.gateway.dto;

/**
 * Chat 请求 DTO。
 */
public record ChatRequest(String message, String sessionId, String taskId, String workspacePath) {
}
