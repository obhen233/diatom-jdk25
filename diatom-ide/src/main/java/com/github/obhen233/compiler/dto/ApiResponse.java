package com.github.obhen233.compiler.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Generic API response wrapper / 通用API响应包装
 *
 * @param <T> data type / 数据类型
 */
@Schema(description = "Generic API response / 通用API响应")
public class ApiResponse<T> {

    @Schema(description = "Whether the request was successful / 请求是否成功")
    private boolean success;

    @Schema(description = "Response message / 响应消息")
    private String message;

    @Schema(description = "Response data / 响应数据")
    private T data;

    public ApiResponse() {}

    public ApiResponse(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, null, data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }

    public static <T> ApiResponse<T> fail(String message, T data) {
        return new ApiResponse<>(false, message, data);
    }

    // Getters and Setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
