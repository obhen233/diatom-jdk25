package com.github.obhen233.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.tool.annotation.ToolMethod;
import com.github.obhen233.core.tool.builtin.CommandTools;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class ToolRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);
    private final Map<String, Tool> tools = new HashMap<>();
    private final Map<String, Method> executors = new HashMap<>();
    private final Map<String, Object> instances = new HashMap<>();
    private final ObjectMapper mapper = JsonUtils.getMapper();

    public void register(Object instance, Method method, ToolMethod ann) {
        String name = ann.name().isEmpty() ? method.getName() : ann.name();
        String description = ann.description();
        String schema = ann.parametersSchema();

        Tool tool = new Tool(name, description, schema);
        // Extract security metadata from annotation
        tool.setReadOnly(ann.readOnly());
        tool.setCheckWorkspaceBoundary(ann.checkWorkspaceBoundary());
        tool.setRequiresConfirmation(ann.requiresConfirmation());
        tool.setRiskLevel(ann.riskLevel());
        tool.setConfirmationTemplate(ann.confirmationTemplate());
        tool.setRiskDescriptionTemplate(ann.riskDescriptionTemplate());

        tools.put(name, tool);
        executors.put(name, method);
        instances.put(name, instance);
        logger.debug("Registered tool: {} (readOnly={}, riskLevel={})", name, ann.readOnly(), ann.riskLevel());
    }

    public void scanObject(Object obj) {
        Class<?> clazz = obj.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(ToolMethod.class)) {
                ToolMethod ann = method.getAnnotation(ToolMethod.class);
                method.setAccessible(true);
                register(obj, method, ann);
            }
        }
    }

    public String execute(String name, String argsJson) throws com.github.obhen233.core.tool.builtin.FileTools.UnauthorizedPathException, UnauthorizedAccessException {
        logger.info("ToolRegistry.execute called: name={}, args={}", name, argsJson);
        Method method = executors.get(name);
        Object instance = instances.get(name);
        if (method == null) {
            logger.warn("Tool not found in registry: {}", name);
            return "Error: Tool not found: " + name;
        }

        try {
            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] args;

            if (paramTypes.length == 0) {
                args = new Object[0];
            } else if (paramTypes.length == 1) {
                // Single parameter: extract the value from JSON
                // If parameter is String, parse JSON and extract the value
                if (paramTypes[0] == String.class && argsJson != null && !argsJson.isEmpty()) {
                    // Try to fix Windows path issues in JSON before parsing
                    String fixedArgsJson = fixWindowsPathInJson(argsJson);

                    try {
                        JsonNode jsonObj = mapper.readTree(fixedArgsJson);

                        // Check if this is a multi-field JSON that should be passed as-is
                        // (e.g., {"cmd":"xxx","args":"xxx"} for run_command)
                        // If JSON has multiple fields, pass as-is
                        if (jsonObj.isObject() && jsonObj.size() > 1) {
                            // Multi-field JSON: pass the original JSON string (with fixes applied)
                            args = new Object[]{fixedArgsJson};
                        } else if (jsonObj.isObject() && jsonObj.size() == 1) {
                            // Single-field JSON: for simplicity, always pass the JSON string
                            // Let the method handle parsing based on its actual parameter name
                            // This avoids issues with arrays/objects where asText() returns empty
                            args = new Object[]{fixedArgsJson};
                        } else {
                            // Primitive JSON (array, string, number): pass as-is
                            args = new Object[]{fixedArgsJson};
                        }
                    } catch (Exception e) {
                        // If JSON parsing fails, pass the original string
                        args = new Object[]{fixedArgsJson};
                    }
                } else {
                    args = new Object[]{argsJson};
                }
            } else {
                JsonNode jsonObj = mapper.readTree(argsJson);
                args = new Object[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    JsonNode argNode = jsonObj.get("arg" + i);
                    if (argNode != null) {
                        if (paramTypes[i] == String.class) {
                            args[i] = argNode.asText();
                        } else if (paramTypes[i] == int.class || paramTypes[i] == Integer.class) {
                            args[i] = argNode.asInt();
                        } else if (paramTypes[i] == boolean.class || paramTypes[i] == Boolean.class) {
                            args[i] = argNode.asBoolean();
                        } else if (paramTypes[i] == long.class || paramTypes[i] == Long.class) {
                            args[i] = argNode.asLong();
                        } else if (paramTypes[i] == double.class || paramTypes[i] == Double.class) {
                            args[i] = argNode.asDouble();
                        } else {
                            args[i] = mapper.treeToValue(argNode, paramTypes[i]);
                        }
                    }
                }
            }

            Object result = method.invoke(instance, args);
            return result != null ? result.toString() : "";
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof com.github.obhen233.core.tool.builtin.FileTools.UnauthorizedPathException) {
                throw (com.github.obhen233.core.tool.builtin.FileTools.UnauthorizedPathException) cause;
            }
            if (cause instanceof CommandTools.CommandNotWhitelistedException) {
                throw (CommandTools.CommandNotWhitelistedException) cause;
            }
            logger.error("Error executing tool: {}", name, e);
            return "Error executing tool " + name + ": " + cause.getMessage();
        } catch (Exception e) {
            if (e instanceof com.github.obhen233.core.tool.builtin.FileTools.UnauthorizedPathException) {
                throw (com.github.obhen233.core.tool.builtin.FileTools.UnauthorizedPathException) e;
            }
            if (e instanceof CommandTools.CommandNotWhitelistedException) {
                throw (CommandTools.CommandNotWhitelistedException) e;
            }
            logger.error("Error executing tool: {}", name, e);
            return "Error executing tool " + name + ": " + e.getMessage();
        }
    }
    
    /**
     * Fix Windows paths in JSON by escaping unescaped backslashes in string values.
     * This handles cases where AI generates paths like "D:\path\file" without proper escaping.
     * 
     * Example: {"path": "D:\\test\\file"} -> {"path": "D:\\\\test\\\\file"}
     * Example: {"args": "D:\\diatom\\run.bat"} -> {"args": "D:\\\\diatom\\\\run.bat"}
     */
    private String fixWindowsPathInJson(String json) {
        if (json == null || !File.separator.equals("\\")) {
            return json;  // Only fix on Windows
        }
        
        StringBuilder result = new StringBuilder();
        int i = 0;
        boolean inString = false;
        
        while (i < json.length()) {
            char c = json.charAt(i);
            
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                // Toggle string state (handle escaped quotes)
                inString = !inString;
                result.append(c);
                i++;
            } else if (inString && c == '\\') {
                // We're inside a string and found a backslash
                if (i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    
                    // Check if this is an already-escaped character
                    if (next == '"' || next == '\\' || next == '/' || next == 'n' || next == 'r' || next == 't' || next == 'u') {
                        // Already escaped, keep as-is
                        result.append(c);
                        result.append(next);
                        i += 2;
                    } else if (Character.isLetter(next) || Character.isDigit(next) || next == '_' || next == '-' || next == '.' || next == ' ') {
                        // Unescaped backslash followed by path character - escape it
                        // This is likely a Windows path like D:\path
                        result.append("\\\\");
                        i++;
                    } else {
                        // Other cases - keep as-is
                        result.append(c);
                        i++;
                    }
                } else {
                    result.append(c);
                    i++;
                }
            } else {
                result.append(c);
                i++;
            }
        }
        
        String fixed = result.toString();
        if (!fixed.equals(json)) {
            logger.debug("Fixed Windows path in JSON: {} -> {}", json, fixed);
        }
        return fixed;
    }

    public static class UnauthorizedAccessException extends RuntimeException {
        private final String path;
        private final String toolName;

        public UnauthorizedAccessException(String message, String path, String toolName) {
            super(message);
            this.path = path;
            this.toolName = toolName;
        }

        public String getPath() { return path; }
        public String getToolName() { return toolName; }
    }

    public Map<String, Tool> getToolDefinitions() {
        return new HashMap<>(tools);
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }
    
    /**
     * Get the tool instance by name.
     * Useful for accessing tool-specific methods like setTimeoutCallback.
     */
    public Object getToolInstance(String toolName) {
        return instances.get(toolName);
    }
}