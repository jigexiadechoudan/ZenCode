package dev.zencode.subagent;

/**
 * 子 Agent 任务执行状态。
 */
public enum TaskStatus {
    /** 运行中。 */
    RUNNING,
    /** 正常完成。 */
    COMPLETED,
    /** 执行失败。 */
    FAILED,
    /** 被取消。 */
    CANCELLED,
    /** 达到最大轮数上限。 */
    ROUND_LIMIT,
    /** 超时终止。 */
    TIMEOUT
}
