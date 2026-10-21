package dev.zencode.provider;

import dev.zencode.chat.ChatMessage;
import dev.zencode.chat.ConversationTurn;

import java.util.List;
import java.util.Map;

/**
 * 统一的 Provider 请求模型。
 * <p>
 * 携带已确认的结构化历史、可选当前用户消息、system prompt 和稳定的工具 Schema。
 * 首轮携带当前用户消息；后续轮将当前用户消息置空，只依赖已提交的历史，不注入空用户消息。
 * </p>
 */
public record ProviderRequest(
        List<ConversationTurn> history,
        List<ChatMessage> transientReminders,
        ChatMessage currentUserMessage,
        String systemPrompt,
        List<Map<String, Object>> toolSchemas) {

    public ProviderRequest {
        history = history == null ? List.of() : List.copyOf(history);
        transientReminders = transientReminders == null ? List.of() : List.copyOf(transientReminders);
        toolSchemas = toolSchemas == null ? List.of() : List.copyOf(toolSchemas);
    }

    /** 便捷构造：无轮次级 reminder，兼容不含 transient 的既有调用。 */
    public ProviderRequest(List<ConversationTurn> history, ChatMessage currentUserMessage,
                           String systemPrompt, List<Map<String, Object>> toolSchemas) {
        this(history, List.of(), currentUserMessage, systemPrompt, toolSchemas);
    }

    /** 是否携带当前用户消息（仅首轮为 true）。 */
    public boolean hasCurrentUserMessage() {
        return currentUserMessage != null;
    }

    /** 是否携带轮次级 reminder（仅当前请求）。 */
    public boolean hasTransientReminders() {
        return !transientReminders.isEmpty();
    }
}
