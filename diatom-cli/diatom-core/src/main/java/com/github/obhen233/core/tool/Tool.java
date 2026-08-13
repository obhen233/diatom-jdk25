package com.github.obhen233.core.tool;

public class Tool {
    private String name;
    private String readableName;
    private String description;
    private String parametersSchema;

    // Security metadata
    private boolean readOnly = false;
    private boolean checkWorkspaceBoundary = false;
    private boolean requiresConfirmation = false;
    private String riskLevel = "none";  // none, low, medium, high, critical
    private String confirmationTemplate = "";
    private String riskDescriptionTemplate = "";

    public Tool() {}

    public Tool(String name, String description, String parametersSchema) {
        this.name = name;
        this.description = description;
        this.parametersSchema = parametersSchema;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getReadableName() { return readableName; }
    public void setReadableName(String readableName) { this.readableName = readableName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getParametersSchema() { return parametersSchema; }
    public void setParametersSchema(String parametersSchema) { this.parametersSchema = parametersSchema; }

    // Security metadata
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }

    public boolean isCheckWorkspaceBoundary() { return checkWorkspaceBoundary; }
    public void setCheckWorkspaceBoundary(boolean checkWorkspaceBoundary) { this.checkWorkspaceBoundary = checkWorkspaceBoundary; }

    public boolean isRequiresConfirmation() { return requiresConfirmation; }
    public void setRequiresConfirmation(boolean requiresConfirmation) { this.requiresConfirmation = requiresConfirmation; }

    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }

    public String getConfirmationTemplate() { return confirmationTemplate; }
    public void setConfirmationTemplate(String confirmationTemplate) { this.confirmationTemplate = confirmationTemplate; }

    public String getRiskDescriptionTemplate() { return riskDescriptionTemplate; }
    public void setRiskDescriptionTemplate(String riskDescriptionTemplate) { this.riskDescriptionTemplate = riskDescriptionTemplate; }
}
