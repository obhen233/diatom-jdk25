package com.github.obhen233.compiler.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AI tool execution progress for WebSocket push.
 *
 * @see com.github.obhen233.compiler.mcp.ProgressPublisher
 */
@Schema(description = "AI tool execution progress / AI工具执行进度")
public class ToolProgress {

    @Schema(description = "Tool name / 工具名称", example = "read_file")
    private String tool;

    @Schema(description = "Target file or path / 目标文件或路径", example = "src/Main.java")
    private String target;

    @Schema(description = "Status: started/reading/writing/completed / 状态")
    private String status;

    @Schema(description = "Timestamp / 时间戳")
    private long timestamp;

    public ToolProgress() {}

    public ToolProgress(String tool, String target, String status) {
        this.tool = tool;
        this.target = target;
        this.status = status;
        this.timestamp = System.currentTimeMillis();
    }

    public static ToolProgress reading(String tool, String target) {
        return new ToolProgress(tool, target, "reading");
    }

    public static ToolProgress writing(String tool, String target) {
        return new ToolProgress(tool, target, "writing");
    }

    public static ToolProgress completed(String tool, String target) {
        return new ToolProgress(tool, target, "completed");
    }

    public static ToolProgress generating() {
        return new ToolProgress("model", "", "generating");
    }

    // Getters and Setters
    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
