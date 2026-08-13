package com.github.obhen233.compiler.debug.model;

public class DebugBreakpoint {
    private String id;
    private String className;
    private String fileName;
    private String filePath; // full relative path e.g. "src/main/java/com/example/Main.java"
    private int lineNumber;
    private boolean enabled;
    private transient Object requestId; // JDI request reference

    public DebugBreakpoint() {}

    public DebugBreakpoint(String id, String className, String fileName, String filePath, int lineNumber) {
        this.id = id;
        this.className = className;
        this.fileName = fileName;
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.enabled = true;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Object getRequestId() { return requestId; }
    public void setRequestId(Object requestId) { this.requestId = requestId; }

}
