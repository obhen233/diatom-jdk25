package com.github.obhen233.compiler.exception;

import com.github.obhen233.compiler.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.stream.Collectors;

/**
 * Global exception handler for REST controllers.
 * Catches unhandled exceptions and returns consistent ApiResponse format.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CompileException.class)
    @ResponseBody
    public ApiResponse<?> handleCompileException(CompileException e) {
        log.warn("Compilation error: {}", e.getMessage());
        return ApiResponse.fail(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ApiResponse<?> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        return ApiResponse.fail(e.getMessage());
    }

    /**
     * {@code @Valid @RequestBody} 校验失败（DTO 字段约束违规）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseBody
    public ApiResponse<?> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Request validation failed: {}", msg);
        return ApiResponse.fail(msg.isEmpty() ? "Validation failed" : msg);
    }

    /**
     * {@code @Validated} 参数校验失败（query param / path variable）。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseBody
    public ApiResponse<?> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("Parameter validation failed: {}", msg);
        return ApiResponse.fail(msg.isEmpty() ? "Validation failed" : msg);
    }

    /**
     * 请求体 JSON 无法解析。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseBody
    public ApiResponse<?> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("Malformed request body: {}", e.getMessage());
        return ApiResponse.fail("Invalid request body");
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public ApiResponse<?> handleGeneric(Exception e) {
        log.error("Unexpected error", e);
        return ApiResponse.fail("Internal server error: " + e.getMessage());
    }
}
