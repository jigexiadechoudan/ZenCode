package dev.zencode.tool;

import java.util.Objects;

/**
 * Provider 无关的工具结果内容块，包含 {@link ToolResult} 和对应 {@link ToolCall} 的关联信息。
 * <p>
 * Provider 负责把它转成 Anthropic {@code tool_result} 或 OpenAI {@code tool} 消息。
 * </p>
 */
public final class ToolResultContent {
    private final ToolResult result;
    private final String callId;
    private final String toolName;

    public ToolResultContent(ToolResult result, String callId, String toolName) {
        this.result = Objects.requireNonNull(result, "工具结果不能为空");
        this.callId = Objects.requireNonNull(callId, "callId 不能为空");
        this.toolName = Objects.requireNonNull(toolName, "toolName 不能为空");
    }

    /** 工具执行结果。 */
    public ToolResult result() { return result; }

    /** 对应的工具调用 ID。 */
    public String callId() { return callId; }

    /** 对应的工具名称。 */
    public String toolName() { return toolName; }
}