package dev.zencode.agent;

/**
 * 标准终止原因，{@link AgentEvent.Terminated} 的唯一结束标志。
 */
public enum AgentTerminationReason {
    /** 正常成功结束：发布了最终回复。 */
    COMPLETED,
    /** 达到轮数上限：发布了上限说明作为最终回复。 */
    ROUND_LIMIT,
    /** 外部取消：不发布最终回复。 */
    CANCELLED,
    /** 模型错误、静默超时或协议错误：发布 Error 事件后终止。 */
    ERROR
}
