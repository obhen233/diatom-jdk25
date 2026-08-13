package com.github.obhen233.core.gateway.task;

/**
 * 任务状态枚举
 */
public enum TaskStatus {
    PENDING("Gateway Agent 分析中"),
    ASSIGNED("已分配 Worker，等待接收"),
    IN_PROGRESS("Worker 执行中"),
    SUSPECT("Worker 疑似失联"),
    SUSPENDED("任务暂停，可迁移"),
    CANCELLING("用户请求取消，等待确认"),
    CANCELLED("用户取消"),
    COMPLETED("正常完成"),
    FAILED("不可恢复错误"),
    TOKEN_EXHAUSTED("Token 耗尽，等待迁移"),
    TIMEOUT_SOON("Gateway 发出超时警告，Worker 还有宽限期"),
    TIMEOUT("任务超时，同 SUSPENDED 可迁移或标记失败");

    private final String description;

    TaskStatus(String description) {
        this.description = description;
    }

    public String getDescription() { return description; }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }

    public boolean isActive() {
        return this == PENDING || this == ASSIGNED || this == IN_PROGRESS || this == TIMEOUT_SOON;
    }
}
