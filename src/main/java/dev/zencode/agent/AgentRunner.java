package dev.zencode.agent;

import dev.zencode.chat.Conversation;

/**
 * Agent 运行启动入口抽象，便于上层注入与测试替身。
 * <p>
 * {@link Agent} 是唯一生产实现；Controller 依赖本接口以支持 FakeAgent 测试。
 * </p>
 */
public interface AgentRunner {

    /**
     * 启动一次 Agent 运行。
     *
     * @param conversation 持久会话
     * @param userMessage 用户消息
     * @param options 运行选项
     * @return 立即返回的运行句柄
     */
    AgentRun run(Conversation conversation, String userMessage, AgentRunOptions options);
}
