package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CollaborationSummaryResponse {
    public CollaborationSummary collaborationSummary;
    public List<SubTaskResult> results;
    public List<FileConflictResponse> fileConflicts;
    public Boolean conflictResolutionNeeded;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CollaborationSummary {
        public int total;
        public int success;
        public int failed;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SubTaskResult {
        public String subTaskId;
        public boolean success;
        public String summary;
        public String detail;
    }
}
