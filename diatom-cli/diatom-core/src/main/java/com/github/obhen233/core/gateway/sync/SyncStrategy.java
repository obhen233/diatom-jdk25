package com.github.obhen233.core.gateway.sync;

/**
 * 项目文件同步策略。
 * <p>
 * 决定 Gateway → Worker 之间如何同步工作区文件。
 */
public enum SyncStrategy {

    /** 全量 zip 推送（小项目轻量补充方案，无共享 NAS/OSS 时使用） */
    FULL_SYNC,

    /** 无需同步（共享 NAS/OSS 文件系统，或任务只读/查询） */
    SKIP
}
