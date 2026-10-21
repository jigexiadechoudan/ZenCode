package dev.zencode.provider;

public sealed interface StreamEvent permits StreamEvent.ThinkingDelta, StreamEvent.TextDelta,
        StreamEvent.ToolCallStart, StreamEvent.ToolCallDelta, StreamEvent.Completed,
        StreamEvent.Failed {
    record ThinkingDelta(String text) implements StreamEvent {}

    record TextDelta(String text) implements StreamEvent {}

    /** 工具调用开始事件，包含调用 ID 和工具名称（仅用于实时展示）。 */
    record ToolCallStart(String callId, String toolName) implements StreamEvent {}

    /** 工具调用参数增量事件（仅用于实时展示）。 */
    record ToolCallDelta(String callId, String argumentsDelta) implements StreamEvent {}

    /**
     * 唯一成功终端事件，携带一个完整的 {@link ProviderResponse}。
     * 每次 Provider 请求只能产生一个终端事件（成功或失败其一）。
     */
    record Completed(ProviderResponse response) implements StreamEvent {}

    /** 唯一失败终端事件。 */
    record Failed(String safeMessage) implements StreamEvent {}
}
