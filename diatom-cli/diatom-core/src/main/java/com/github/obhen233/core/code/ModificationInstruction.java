package com.github.obhen233.core.code;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型返回的修改指令
 */
public class ModificationInstruction {
    private String filePath;
    private ModificationType type;
    private String target;           // 方法名/类名/行号
    private String newCode;          // 压缩格式的新代码
    private Map<String, String> context = new HashMap<>();

    public enum ModificationType {
        REPLACE_METHOD_BODY,     // 替换方法体
        ADD_METHOD,              // 添加方法
        ADD_FIELD,               // 添加字段
        ADD_IMPORT,              // 添加 import
        MODIFY_CLASS_BODY,      // 修改类内部
        REPLACE_FILE,            // 整个文件替换
        INSERT_STATEMENT,        // 插入语句
    }

    public ModificationInstruction() {}

    public ModificationInstruction(String filePath, ModificationType type, String target, String newCode) {
        this.filePath = filePath;
        this.type = type;
        this.target = target;
        this.newCode = newCode;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public ModificationType getType() {
        return type;
    }

    public void setType(ModificationType type) {
        this.type = type;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getNewCode() {
        return newCode;
    }

    public void setNewCode(String newCode) {
        this.newCode = newCode;
    }

    public Map<String, String> getContext() {
        return context;
    }

    public void setContext(Map<String, String> context) {
        this.context = context;
    }

    public void addContext(String key, String value) {
        this.context.put(key, value);
    }

    @Override
    public String toString() {
        return "ModificationInstruction{" +
                "filePath='" + filePath + '\'' +
                ", type=" + type +
                ", target='" + target + '\'' +
                ", newCode='" + (newCode != null ? newCode.substring(0, Math.min(50, newCode.length())) + "..." : "null") + '\'' +
                '}';
    }
}
