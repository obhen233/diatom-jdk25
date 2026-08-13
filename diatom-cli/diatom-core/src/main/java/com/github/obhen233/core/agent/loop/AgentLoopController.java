package com.github.obhen233.core.agent.loop;

/**
 * Agent 主循环编排器。
 * 管理多步执行流程、选择性工具发送、错误恢复、检查点集成。
 *
 * <p>从 {@code ReActAgent.run()} 中提取的主循环逻辑，
 * 将 ~700 行的循环体分离到独立的实现类中。</p>
 */
public interface AgentLoopController {

    /**
     * 运行完整的 agent 循环。
     *
     * @param context 循环上下文，包含所有必要依赖和状态
     * @return 最终结果字符串
     */
    String runLoop(LoopContext context);
}
