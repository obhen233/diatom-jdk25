package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileConflictResponse {
    public String file;
    public List<ConflictModification> modifications;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConflictModification {
        public String subTaskId;
        public String changeType;
        public String unifiedDiff;
        public Boolean hasOldContent;
        public Boolean hasNewContent;
    }
}
