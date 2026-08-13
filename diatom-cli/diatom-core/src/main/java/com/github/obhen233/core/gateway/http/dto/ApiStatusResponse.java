package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiStatusResponse {
    public String status;
    public String error;
    public String output;

    public ApiStatusResponse() {}

    public ApiStatusResponse(String status) {
        this.status = status;
    }

    public ApiStatusResponse(String status, String error) {
        this.status = status;
        this.error = error;
    }

    public static ApiStatusResponse ok() {
        return new ApiStatusResponse("ok");
    }

    public static ApiStatusResponse ok(String status) {
        return new ApiStatusResponse(status);
    }

    public static ApiStatusResponse error(String error) {
        return new ApiStatusResponse("error", error);
    }
}
