package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class LockTokenResponse {
    public String status;
    public String token;
    public String error;
    public String workerId;
    public String resourceId;
    public long expiry;

    public static LockTokenResponse ok(String token) {
        LockTokenResponse r = new LockTokenResponse();
        r.status = "ok";
        r.token = token;
        return r;
    }

    public static LockTokenResponse error(String msg) {
        LockTokenResponse r = new LockTokenResponse();
        r.status = "error";
        r.error = msg;
        return r;
    }
}
