package dev.zencode.provider;

import dev.zencode.chat.ProviderTurnState;
import dev.zencode.chat.Usage;

import java.util.List;

/**
 * 一个完整的模型响应。
 * <p>
 * 包含聚合后的 thinking、文本、按模型响应顺序排列的全部工具调用（含解析失败的调用）、
 * 标准化结束原因、token 用量和 Provider 轮次状态。
 * 实时增量展示仍由 {@link StreamEvent.ThinkingDelta} 与 {@link StreamEvent.TextDelta} 负责，
 * 本对象是 Agent 提交历史与判定循环的唯一依据。
 * </p>
 */
public record ProviderResponse(
        String thinking,
        String text,
        List<ToolCallAssembler.AssembledCall> toolCalls,
        ProviderStopReason stopReason,
        Usage usage,
        ProviderTurnState providerState) {

    public ProviderResponse {
        thinking = thinking == null ? "" : thinking;
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 是否包含工具调用。 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
