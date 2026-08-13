package com.github.obhen233.compiler.debug.model;

import java.util.List;

public class DebugStackFrame {
    private long threadId;
    private int frameId;
    private String className;
    private String methodName;
    private String fileName;
    private int lineNumber;
    private List<DebugVariable> variables;

    public long getThreadId() { return threadId; }
    public void setThreadId(long threadId) { this.threadId = threadId; }
    public int getFrameId() { return frameId; }
    public void setFrameId(int frameId) { this.frameId = frameId; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }
    public List<DebugVariable> getVariables() { return variables; }
    public void setVariables(List<DebugVariable> variables) { this.variables = variables; }
}
