package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MonitorStatusResponse {
    public int onlineWorkers;
    public int activeTasks;
    public int pendingTasks;
    public int totalTasks;
    public int queueDepth;
    public boolean authConfigured;
    public ConcurrencyInfo concurrency;
    public List<WorkerInfoWithActiveRequests> workers;
    public List<TaskStateSummary> tasks;
    public List<Object[]> workerLoadHistory;
    public List<Object[]> pendingHistory;
}
