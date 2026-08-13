package com.github.obhen233.compiler.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Compilation execution result / 编译执行结果
 */
@Schema(description = "Compilation execution result / 编译执行结果")
public class CompileResult {

    @Schema(description = "Execution output / 执行输出")
    private String result;

    @Schema(description = "Execution duration in milliseconds / 执行耗时(毫秒)")
    private Long durationTime;

    @Schema(description = "Result type: ok, fail, error / 结果类型")
    private String type;

    @Schema(description = "Optional message / 可选消息")
    private String message;

    public CompileResult() {}

    public CompileResult(String result, Long durationTime, String type, String message) {
        this.result = result;
        this.durationTime = durationTime;
        this.type = type;
        this.message = message;
    }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public Long getDurationTime() { return durationTime; }
    public void setDurationTime(Long durationTime) { this.durationTime = durationTime; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
