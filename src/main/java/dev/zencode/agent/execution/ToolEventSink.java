package dev.zencode.agent.execution;

import dev.zencode.tool.ToolResult;

/**
 * 工具执行事件的发布入口。
 * <p>
 * 供批处理器按模型顺序发布工具开始与结果事件。实现方负责转换为 Agent 事件流。
 * </p>
 */
public interface ToolEventSink {

    /** 发布工具开始事件。safeSummary 为安全参数摘要。 */
    void started(String callId, String toolName, String safeSummary, int round);

    /** 发布工具结果事件。 */
    void finished(String callId, String toolName, ToolResult result, int round);

    /** 空实现：忽略全部事件，用于纯执行不关心展示的场景。 */
    ToolEventSink NOOP = new ToolEventSink() {
        @Override
        public void started(String callId, String toolName, String safeSummary, int round) {
        }

        @Override
        public void finished(String callId, String toolName, ToolResult result, int round) {
        }
    };
}
