package dev.zencode.agent;

import dev.zencode.chat.Usage;
import dev.zencode.tool.ToolResult;

import java.util.Objects;

/**
 * UI 无关的 Agent 事件协议。
 * <p>
 * 覆盖用户消息、模型 thinking 增量、模型文本增量、工具调用开始、工具结果、
 * 最终回复、错误和终止。上层无需访问 Agent 内部状态即可还原执行过程。
 * {@link Terminated} 是唯一事件流结束标志。
 * 工具事件只携带安全摘要或结构化安全结果，不携带未经脱敏的完整参数。
 * </p>
 */
public sealed interface AgentEvent {

    /** A model request is about to start; carries no prompt or credentials. */
    record ModelStarted(int round) implements AgentEvent {}

    /** 本次运行的用户消息（第一轮发布）。 */
    record UserMessage(String text) implements AgentEvent {
        public UserMessage {
            Objects.requireNonNull(text, "文本不能为空");
        }
    }

    /** 模型 thinking 增量。 */
    record ThinkingDelta(String text, int round) implements AgentEvent {
        public ThinkingDelta {
            Objects.requireNonNull(text, "文本不能为空");
        }
    }

    /** 模型文本增量。 */
    record TextDelta(String text, int round) implements AgentEvent {
        public TextDelta {
            Objects.requireNonNull(text, "文本不能为空");
        }
    }

    /** 工具调用开始，safeSummary 为安全参数摘要。 */
    record ToolStarted(String callId, String toolName, String safeSummary, int round) implements AgentEvent {
        public ToolStarted {
            Objects.requireNonNull(callId, "调用 ID 不能为空");
            Objects.requireNonNull(toolName, "工具名不能为空");
            safeSummary = safeSummary == null ? "" : safeSummary;
        }
    }

    /** 工具执行完成，携带结构化安全结果。 */
    record ToolFinished(String callId, String toolName, ToolResult result, int round) implements AgentEvent {
        public ToolFinished {
            Objects.requireNonNull(callId, "调用 ID 不能为空");
            Objects.requireNonNull(toolName, "工具名不能为空");
            Objects.requireNonNull(result, "工具结果不能为空");
        }
    }

    /** 唯一模型最终回复（正常成功或轮数上限时发布）。 */
    record FinalReply(String text) implements AgentEvent {
        public FinalReply {
            Objects.requireNonNull(text, "文本不能为空");
        }
    }

    /** 某轮已提交并携带该轮用量（普通中间事件，每轮结束即刷新 UI 用）。 */
    record RoundUsageCommitted(int round, Usage usage) implements AgentEvent {
        public RoundUsageCommitted {
            if (round < 1) throw new IllegalArgumentException("轮次必须为正数: " + round);
        }
    }

    /** 需要用户 HITL 确认的权限请求；携带脱敏摘要，不携带完整参数。 */
    record PermissionRequested(String callId, String toolName, String safeSummary, String reason)
            implements AgentEvent {
        public PermissionRequested {
            Objects.requireNonNull(callId, "调用 ID 不能为空");
            Objects.requireNonNull(toolName, "工具名不能为空");
            safeSummary = safeSummary == null ? "" : safeSummary;
            reason = reason == null ? "" : reason;
        }
    }

    /** 面向用户的安全错误，不携带异常堆栈或敏感信息。 */
    record Error(String safeMessage) implements AgentEvent {
        public Error {
            Objects.requireNonNull(safeMessage, "错误消息不能为空");
        }
    }

    /** 唯一事件流结束标志。 */
    record Terminated(AgentTerminationReason reason, int completedRounds) implements AgentEvent {
        public Terminated {
            Objects.requireNonNull(reason, "终止原因不能为空");
        }
    }
}
