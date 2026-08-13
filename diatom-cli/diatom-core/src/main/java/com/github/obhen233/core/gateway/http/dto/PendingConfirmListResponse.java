package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PendingConfirmListResponse {
    public List<PendingConfirmItem> pending;

    public static class PendingConfirmItem {
        public String requestId;
        public String workerId;
        public String toolName;
        public String action;
        public String arguments;
        public String toolCallId;
    }
}
