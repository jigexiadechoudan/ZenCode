package dev.zencode.chat;

import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolResultContent;

import java.util.*;

/**
 * 结构化会话内容块，支持用户文本、助手文本/思考、助手工具调用和工具结果。
 * <p>
 * 保留内容顺序，并兼容已有纯文本轮次。
 * 每一轮次包含一个或多个内容块，按顺序表示交互中的事件序列。
 * </p>
 */
public sealed interface ConversationContent {

    /** 返回内容类型标识。 */
    ContentType type();

    /** 内容类型枚举。 */
    enum ContentType {
        USER_TEXT,
        ASSISTANT_TEXT,
        THINKING_TEXT,
        TOOL_CALL,
        TOOL_RESULT
    }

    /** 用户文本内容块。 */
    record UserText(String text) implements ConversationContent {
        public UserText {
            Objects.requireNonNull(text, "文本不能为空");
        }

        @Override
        public ContentType type() { return ContentType.USER_TEXT; }
    }

    /** 助手文本内容块。 */
    record AssistantText(String text) implements ConversationContent {
        public AssistantText {
            Objects.requireNonNull(text, "文本不能为空");
        }

        @Override
        public ContentType type() { return ContentType.ASSISTANT_TEXT; }
    }

    /** 助手思考内容块。 */
    record ThinkingText(String text) implements ConversationContent {
        public ThinkingText {
            Objects.requireNonNull(text, "文本不能为空");
        }

        @Override
        public ContentType type() { return ContentType.THINKING_TEXT; }
    }

    /** 工具调用内容块。 */
    record ToolCallBlock(ToolCall toolCall) implements ConversationContent {
        public ToolCallBlock {
            Objects.requireNonNull(toolCall, "工具调用不能为空");
        }

        @Override
        public ContentType type() { return ContentType.TOOL_CALL; }

        /** 工具调用 ID。 */
        public String callId() { return toolCall.callId(); }

        /** 工具名称。 */
        public String toolName() { return toolCall.toolName(); }
    }

    /** 工具结果内容块。 */
    record ToolResultBlock(ToolResultContent toolResult) implements ConversationContent {
        public ToolResultBlock {
            Objects.requireNonNull(toolResult, "工具结果不能为空");
        }

        @Override
        public ContentType type() { return ContentType.TOOL_RESULT; }

        /** 对应的工具调用 ID。 */
        public String callId() { return toolResult.callId(); }

        /** 工具名称。 */
        public String toolName() { return toolResult.toolName(); }
    }

    /**
     * 从纯文本构造简单的用户文本和助手文本内容块列表。
     * 用于兼容已有纯文本轮次。
     */
    static List<ConversationContent> fromSimpleMessages(String userText, String assistantText) {
        List<ConversationContent> contents = new ArrayList<>();
        if (userText != null && !userText.isBlank()) {
            contents.add(new UserText(userText));
        }
        if (assistantText != null) {
            contents.add(new AssistantText(assistantText));
        }
        return Collections.unmodifiableList(contents);
    }
}