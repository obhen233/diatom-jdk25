package com.github.obhen233.cli.execute;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Structured result of a single agent execution in --execute mode.
 * <p>
 * Designed for serialization to JSON/XML/HTML output formats.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecuteResult {

    private String response;
    private String status;
    private String error;
    private String taskId;
    private TokenUsage tokenUsage;
    private FileChanges fileChanges;

    public ExecuteResult() {}

    public ExecuteResult(String response, String status) {
        this.response = response;
        this.status = status;
    }

    public String getResponse() { return response; }
    public void setResponse(String response) { this.response = response; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    @JsonProperty("task_id")
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    @JsonProperty("token_usage")
    public TokenUsage getTokenUsage() { return tokenUsage; }
    public void setTokenUsage(TokenUsage tokenUsage) { this.tokenUsage = tokenUsage; }

    @JsonProperty("file_changes")
    public FileChanges getFileChanges() { return fileChanges; }
    public void setFileChanges(FileChanges fileChanges) { this.fileChanges = fileChanges; }

    /** Convenience factory for a successful result. */
    public static ExecuteResult success(String response) {
        return new ExecuteResult(response, "SUCCESS");
    }

    /** Convenience factory for an error result. */
    public static ExecuteResult error(String errorMessage) {
        ExecuteResult r = new ExecuteResult(null, "ERROR");
        r.setError(errorMessage);
        return r;
    }

    // ==================== Nested Types ====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TokenUsage {
        @JsonProperty("prompt_tokens")
        private long promptTokens;
        @JsonProperty("completion_tokens")
        private long completionTokens;
        @JsonProperty("total_tokens")
        private long totalTokens;

        public TokenUsage() {}

        public TokenUsage(long prompt, long completion, long total) {
            this.promptTokens = prompt;
            this.completionTokens = completion;
            this.totalTokens = total;
        }

        public long getPromptTokens() { return promptTokens; }
        public void setPromptTokens(long v) { this.promptTokens = v; }
        public long getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(long v) { this.completionTokens = v; }
        public long getTotalTokens() { return totalTokens; }
        public void setTotalTokens(long v) { this.totalTokens = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FileChanges {
        @JsonProperty("files_created")
        private List<String> filesCreated;
        @JsonProperty("files_modified")
        private List<String> filesModified;
        @JsonProperty("files_deleted")
        private List<String> filesDeleted;

        public FileChanges() {}

        public FileChanges(List<String> created, List<String> modified, List<String> deleted) {
            this.filesCreated = created != null ? created : Collections.emptyList();
            this.filesModified = modified != null ? modified : Collections.emptyList();
            this.filesDeleted = deleted != null ? deleted : Collections.emptyList();
        }

        public List<String> getFilesCreated() { return filesCreated; }
        public void setFilesCreated(List<String> v) { this.filesCreated = v; }
        public List<String> getFilesModified() { return filesModified; }
        public void setFilesModified(List<String> v) { this.filesModified = v; }
        public List<String> getFilesDeleted() { return filesDeleted; }
        public void setFilesDeleted(List<String> v) { this.filesDeleted = v; }

        public boolean isEmpty() {
            return isEmpty(filesCreated) && isEmpty(filesModified) && isEmpty(filesDeleted);
        }

        private boolean isEmpty(List<String> list) {
            return list == null || list.isEmpty();
        }
    }
}
