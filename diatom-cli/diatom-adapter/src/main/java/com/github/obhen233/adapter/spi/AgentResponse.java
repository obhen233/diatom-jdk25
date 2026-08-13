package com.github.obhen233.adapter.spi;

import java.util.List;

/**
 * Response DTO returned by AgentAdapter.execute().
 *
 * @param response     the agent's textual response
 * @param status       completion status (see STATUS_* constants)
 * @param errorMessage error detail when status is {@link #STATUS_ERROR}
 * @param fileDiffs    file change records produced by the agent
 */
public record AgentResponse(
        String response,
        String status,
        String errorMessage,
        List<FileDiff> fileDiffs) {

    /** Predefined status constants */
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_CANCELLED = "cancelled";

    /**
     * A file change record produced by the agent.
     *
     * @param relativePath the file's path relative to the workspace root
     * @param changeType   "added"/"modified"/"deleted"
     * @param newContent   the file's new content (may be null)
     * @param oldContent   the file's old content (may be null)
     */
    public record FileDiff(String relativePath, String changeType, String newContent, String oldContent) {}
}
