package dev.zencode.session;

import dev.zencode.chat.ConversationTurn;

import java.util.List;

/**
 * 会话恢复结果，包含恢复后的轮次列表与完整性信息。
 *
 * @param turns         恢复后的轮次列表（已截断至最后一个完整轮次）
 * @param trimmedCallId 因 tool_use 未配对而被截断的最后一个 callId（无截断时为 null）
 * @param skippedLines  跳过坏行数
 */
public record SessionLoadResult(List<ConversationTurn> turns, String trimmedCallId, int skippedLines) {
}
