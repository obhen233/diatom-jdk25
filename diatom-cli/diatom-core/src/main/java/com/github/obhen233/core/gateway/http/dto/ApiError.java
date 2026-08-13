package com.github.obhen233.core.gateway.http.dto;

public class ApiError {
    public String error;

    public ApiError() {}

    public ApiError(String error) {
        this.error = error;
    }

    public static ApiError of(String error) {
        return new ApiError(error);
    }
}
