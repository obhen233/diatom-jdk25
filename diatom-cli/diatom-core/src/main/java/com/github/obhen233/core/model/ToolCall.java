package com.github.obhen233.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolCall {
    private String id;

    @JsonProperty("function")
    private Function function;

    private Integer index;

    public ToolCall() {}

    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.function = new Function();
        this.function.name = name;
        this.function.arguments = arguments;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Integer getIndex() { return index; }
    public void setIndex(Integer index) { this.index = index; }

    public String getName() { return function != null ? function.name : null; }
    public String getArguments() { return function != null ? function.arguments : null; }

    public void setName(String name) {
        if (function == null) function = new Function();
        function.name = name;
    }

    public void setArguments(String arguments) {
        if (function == null) function = new Function();
        function.arguments = arguments;
    }

    public Function getFunction() { return function; }
    public void setFunction(Function function) { this.function = function; }

    public static class Function {
        private String name;
        private String arguments;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getArguments() { return arguments; }
        public void setArguments(String arguments) { this.arguments = arguments; }
    }
}