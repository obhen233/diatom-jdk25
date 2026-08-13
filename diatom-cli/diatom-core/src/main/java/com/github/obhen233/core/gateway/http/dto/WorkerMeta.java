package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkerMeta {
    public String instanceId;
    public boolean auditEnabled;
    public List<AuditEntry> auditEntries;
    public FileChanges fileChanges;
}
