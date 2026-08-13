package com.github.obhen233.core.agent;

public class ToolExecutionException extends RuntimeException {
    private final String toolName;
    private final String argsJson;
    private final int retryCount;
    private final String errorDetail;

    public ToolExecutionException(String toolName, String argsJson, int retryCount, String errorDetail) {
        super("工具执行失败: " + toolName + " (retry " + retryCount + ")");
        this.toolName = toolName;
        this.argsJson = argsJson;
        this.retryCount = retryCount;
        this.errorDetail = errorDetail;
    }

    public String getToolName() { return toolName; }
    public String getArgsJson() { return argsJson; }
    public int getRetryCount() { return retryCount; }
    public String getErrorDetail() { return errorDetail; }
}