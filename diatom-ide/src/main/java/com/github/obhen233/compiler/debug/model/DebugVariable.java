package com.github.obhen233.compiler.debug.model;

import java.util.List;

public class DebugVariable {
    private String name;
    private String type;
    private String value;
    private boolean primitive;
    private boolean nul;
    private List<DebugVariable> children;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public boolean isPrimitive() { return primitive; }
    public void setPrimitive(boolean primitive) { this.primitive = primitive; }
    public boolean isNul() { return nul; }
    public void setNul(boolean nul) { this.nul = nul; }
    public List<DebugVariable> getChildren() { return children; }
    public void setChildren(List<DebugVariable> children) { this.children = children; }
}
