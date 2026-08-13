package com.github.obhen233.core.security;

/**
 * Context provided to AutoApprovalStrategy for making approval decisions.
 */
public class ApprovalContext {
    private final String toolName;
    private final String argsJson;
    private final String aiClassification;
    private final SandboxLevel sandboxLevel;
    private final boolean outsideWorkspace;
    private final String riskLevel;
    private final String filePath;
    private final String command;

    private ApprovalContext(Builder builder) {
        this.toolName = builder.toolName;
        this.argsJson = builder.argsJson;
        this.aiClassification = builder.aiClassification;
        this.sandboxLevel = builder.sandboxLevel;
        this.outsideWorkspace = builder.outsideWorkspace;
        this.riskLevel = builder.riskLevel;
        this.filePath = builder.filePath;
        this.command = builder.command;
    }

    public String getToolName() { return toolName; }
    public String getArgsJson() { return argsJson; }
    public String getAiClassification() { return aiClassification; }
    public SandboxLevel getSandboxLevel() { return sandboxLevel; }
    public boolean isOutsideWorkspace() { return outsideWorkspace; }
    public String getRiskLevel() { return riskLevel; }
    public String getFilePath() { return filePath; }
    public String getCommand() { return command; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String toolName;
        private String argsJson;
        private String aiClassification;
        private SandboxLevel sandboxLevel;
        private boolean outsideWorkspace;
        private String riskLevel;
        private String filePath;
        private String command;

        public Builder toolName(String toolName) { this.toolName = toolName; return this; }
        public Builder argsJson(String argsJson) { this.argsJson = argsJson; return this; }
        public Builder aiClassification(String aiClassification) { this.aiClassification = aiClassification; return this; }
        public Builder sandboxLevel(SandboxLevel sandboxLevel) { this.sandboxLevel = sandboxLevel; return this; }
        public Builder outsideWorkspace(boolean outsideWorkspace) { this.outsideWorkspace = outsideWorkspace; return this; }
        public Builder riskLevel(String riskLevel) { this.riskLevel = riskLevel; return this; }
        public Builder filePath(String filePath) { this.filePath = filePath; return this; }
        public Builder command(String command) { this.command = command; return this; }

        public ApprovalContext build() {
            return new ApprovalContext(this);
        }
    }
}
