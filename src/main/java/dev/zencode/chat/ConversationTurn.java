package dev.zencode.chat;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 对话轮次。支持纯文本轮次（user + assistant）和结构化内容块轮次（含工具调用）。
 * <p>
 * 纯文本轮次通过 {@link #userMessage()} 和 {@link #assistantMessage()} 兼容传统访问。
 * 结构化轮次通过 {@link #contents()} 获取完整内容序列。
 * 工具调用轮次保留完整内容顺序，供下一次请求重建消息。
 * </p>
 */
public final class ConversationTurn {
    private final ChatMessage userMessage;
    private final ChatMessage assistantMessage;
    private final List<ConversationContent> contents;
    private final ProviderTurnState providerState;
    private final Usage usage;
    private final SeedType seedType;

    /** 纯文本轮次构造（已有方式）。 */
    public ConversationTurn(ChatMessage userMessage, ChatMessage assistantMessage,
                            ProviderTurnState providerState, Usage usage) {
        Objects.requireNonNull(userMessage, "用户消息不能为空");
        Objects.requireNonNull(assistantMessage, "助手消息不能为空");
        if (userMessage.role() != Role.USER || assistantMessage.role() != Role.ASSISTANT) {
            throw new IllegalArgumentException("轮次必须按 user、assistant 顺序排列");
        }
        this.userMessage = userMessage;
        this.assistantMessage = assistantMessage;
        this.contents = ConversationContent.fromSimpleMessages(userMessage.text(), assistantMessage.text());
        this.providerState = providerState;
        this.usage = usage;
        this.seedType = null;
    }

    /** 结构化内容块轮次构造。userMessage 和 assistantMessage 从内容块派生。 */
    public ConversationTurn(List<ConversationContent> contents,
                            ProviderTurnState providerState, Usage usage) {
        this(contents, providerState, usage, null);
    }

    public ConversationTurn(List<ConversationContent> contents,
                            ProviderTurnState providerState, Usage usage, SeedType seedType) {
        Objects.requireNonNull(contents, "内容块列表不能为空");
        if (contents.isEmpty()) throw new IllegalArgumentException("内容块列表不能为空");
        this.contents = List.copyOf(contents);
        this.userMessage = deriveUserMessage(contents);
        this.assistantMessage = deriveAssistantMessage(contents);
        this.providerState = providerState;
        this.usage = usage;
        this.seedType = seedType;
    }

    /**
     * 只含单条用户文本内容的轮次（无助手内容、无工具、无用量与 Provider 状态）。
     * 用于会话首条环境 `<system-reminder>` 等以 user 角色长期携带的消息。
     */
    public static ConversationTurn userTextOnly(String text) {
        Objects.requireNonNull(text, "文本不能为空");
        return new ConversationTurn(List.of(new ConversationContent.UserText(text)), null, null);
    }

    /** 显式标记不可在压缩时丢弃的 seed 轮次。 */
    public static ConversationTurn seedTextOnly(SeedType type, String text) {
        Objects.requireNonNull(type, "seed 类型不能为空");
        return new ConversationTurn(List.of(new ConversationContent.UserText(text)), null, null, type);
    }

    /**
     * 用户消息。对纯文本轮次为原始用户消息，对结构化轮次从内容块中提取。
     * 后续轮（仅包含助手内容、工具调用与工具结果）没有用户文本块时为 {@code null}，
     * Provider 序列化时不得据此派生空用户消息。
     */
    public ChatMessage userMessage() { return userMessage; }

    /** 是否包含用户文本块。 */
    public boolean hasUserText() { return userMessage != null; }

    /** 助手消息。对纯文本轮次为原始助手消息，对结构化轮次从内容块中拼接文本。 */
    public ChatMessage assistantMessage() { return assistantMessage; }

    /** 所有内容块（不可变列表）。 */
    public List<ConversationContent> contents() { return contents; }

    /** Provider 状态。 */
    public ProviderTurnState providerState() { return providerState; }

    /** Token 用量。 */
    public Usage usage() { return usage; }
    public SeedType seedType() { return seedType; }

    /** 是否包含工具调用。 */
    public boolean hasToolCalls() {
        return contents.stream().anyMatch(c -> c.type() == ConversationContent.ContentType.TOOL_CALL);
    }

    /** 是否包含工具结果。 */
    public boolean hasToolResults() {
        return contents.stream().anyMatch(c -> c.type() == ConversationContent.ContentType.TOOL_RESULT);
    }

    /** 从内容块中提取用户消息文本；没有用户文本块时返回 null（后续轮）。 */
    private static ChatMessage deriveUserMessage(List<ConversationContent> contents) {
        for (ConversationContent content : contents) {
            if (content instanceof ConversationContent.UserText userText) {
                return new ChatMessage(Role.USER, userText.text());
            }
        }
        return null;
    }

    /** 从内容块中拼接助手文本消息。 */
    private static ChatMessage deriveAssistantMessage(List<ConversationContent> contents) {
        StringBuilder text = new StringBuilder();
        for (ConversationContent content : contents) {
            if (content instanceof ConversationContent.AssistantText at) {
                if (text.length() > 0) text.append("\n");
                text.append(at.text());
            }
            if (content instanceof ConversationContent.ThinkingText tt) {
                if (text.length() > 0) text.append("\n");
                text.append("【思考】").append(tt.text());
            }
        }
        return new ChatMessage(Role.ASSISTANT, text.length() > 0 ? text.toString() : "(无文本)");
    }
}
