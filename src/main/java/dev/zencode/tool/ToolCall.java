package dev.zencode.tool;

import java.util.Map;
import java.util.Objects;

/**
 * Provider 无关的工具调用内容块。
 * <p>
 * 保留调用 ID、工具名、解析后参数、原始参数 JSON 和来源协议。
 * 用于在会话历史中保存工具调用信息，并供 Provider 重建协议消息。
 * </p>
 */
public final class ToolCall {
    private final String callId;
    private final String toolName;
    private final Map<String, Object> arguments;
    private final String rawArguments;
    private final String source; // "anthropic" 或 "openai"

    public ToolCall(String callId, String toolName, Map<String, Object> arguments,
                    String rawArguments, String source) {
        this.callId = Objects.requireNonNull(callId, "callId 不能为空");
        this.toolName = Objects.requireNonNull(toolName, "toolName 不能为空");
        this.arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        this.rawArguments = rawArguments == null ? "" : rawArguments;
        this.source = Objects.requireNonNull(source, "source 不能为空");
    }

    /** 模型分配的工具调用 ID。 */
    public String callId() { return callId; }

    /** 工具名称。 */
    public String toolName() { return toolName; }

    /** 解析后的结构化参数。 */
    public Map<String, Object> arguments() { return arguments; }

    /** 流式拼接得到的原始 JSON 文本。 */
    public String rawArguments() { return rawArguments; }

    /** 来源协议（anthropic 或 openai）。 */
    public String source() { return source; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ToolCall toolCall)) return false;
        return callId.equals(toolCall.callId) && toolName.equals(toolCall.toolName)
                && arguments.equals(toolCall.arguments) && source.equals(toolCall.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(callId, toolName, arguments, source);
    }

    @Override
    public String toString() {
        return "ToolCall{callId='" + callId + "', toolName='" + toolName + "'}";
    }
}