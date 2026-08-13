package com.github.obhen233.core.gateway.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Worker 能力自述文档模型
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CapabilityProfile {

    // 能力等级枚举
    public enum CapabilityLevel {
        REQUIRED, PREFERRED, NORMAL
    }

    private String workerId;
    private String model;
    private String apiProvider;
    private String tier;
    private List<String> strengths = new ArrayList<>();
    private List<String> boundaries = new ArrayList<>();
    private List<String> suitableTaskTypes = new ArrayList<>();
    private List<String> unsuitableTaskTypes = new ArrayList<>();
    private Map<String, Double> inferredCapabilities = new HashMap<>();
    private Map<String, CapabilityLevel> capabilityLevels = new HashMap<>();
    private int maxSteps;
    private int maxTokens;
    private int maxOutputTokens;
    private boolean supportsToolCalls;
    private boolean supportsStreaming;
    private String summary;
    private String rawMarkdown;

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiProvider() { return apiProvider; }
    public void setApiProvider(String apiProvider) { this.apiProvider = apiProvider; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
    public List<String> getStrengths() { return strengths; }
    public void setStrengths(List<String> strengths) { this.strengths = strengths; }
    public List<String> getBoundaries() { return boundaries; }
    public void setBoundaries(List<String> boundaries) { this.boundaries = boundaries; }
    public List<String> getSuitableTaskTypes() { return suitableTaskTypes; }
    public void setSuitableTaskTypes(List<String> suitableTaskTypes) { this.suitableTaskTypes = suitableTaskTypes; }
    public List<String> getUnsuitableTaskTypes() { return unsuitableTaskTypes; }
    public void setUnsuitableTaskTypes(List<String> unsuitableTaskTypes) { this.unsuitableTaskTypes = unsuitableTaskTypes; }
    public Map<String, Double> getInferredCapabilities() { return inferredCapabilities; }
    public void setInferredCapabilities(Map<String, Double> inferredCapabilities) { this.inferredCapabilities = inferredCapabilities; }
    public Map<String, CapabilityLevel> getCapabilityLevels() { return capabilityLevels; }
    public void setCapabilityLevels(Map<String, CapabilityLevel> capabilityLevels) { this.capabilityLevels = capabilityLevels; }
    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public boolean isSupportsToolCalls() { return supportsToolCalls; }
    public void setSupportsToolCalls(boolean supportsToolCalls) { this.supportsToolCalls = supportsToolCalls; }
    public boolean isSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(boolean supportsStreaming) { this.supportsStreaming = supportsStreaming; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getRawMarkdown() { return rawMarkdown; }
    public void setRawMarkdown(String rawMarkdown) { this.rawMarkdown = rawMarkdown; }
}
