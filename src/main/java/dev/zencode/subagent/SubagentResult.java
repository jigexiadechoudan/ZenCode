package dev.zencode.subagent;

import dev.zencode.chat.Usage;

/**
 * 子 Agent 一次执行的最终结果，回传给父 Agent。
 *
 * @param taskId 本次子任务唯一 ID
 * @param status 执行状态
 * @param output 最后一条文本或可读错误消息
 * @param usage  汇总 token 消耗（复用 {@link Usage}）
 */
public record SubagentResult(String taskId, TaskStatus status, String output, Usage usage) {

    public SubagentResult {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("任务 ID 不能为空");
        if (status == null) throw new IllegalArgumentException("任务状态不能为空");
        output = output == null ? "" : output;
        usage = usage == null ? Usage.unknown() : usage;
    }
}
