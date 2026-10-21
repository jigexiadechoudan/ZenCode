package dev.zencode.chat;

import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolResultContent;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 结构化会话内容测试。
 */
class ConversationToolContentTest {

    @Test
    void simpleUserTextContent() {
        ConversationContent content = new ConversationContent.UserText("你好");
        assertEquals(ConversationContent.ContentType.USER_TEXT, content.type());
        assertEquals("你好", ((ConversationContent.UserText) content).text());
    }

    @Test
    void assistantTextContent() {
        ConversationContent content = new ConversationContent.AssistantText("回答");
        assertEquals(ConversationContent.ContentType.ASSISTANT_TEXT, content.type());
    }

    @Test
    void toolCallContent() {
        ToolCall call = new ToolCall("call-1", "ReadFile", Map.of("path", "test.txt"), "{\"path\":\"test.txt\"}", "anthropic");
        ConversationContent content = new ConversationContent.ToolCallBlock(call);
        assertEquals(ConversationContent.ContentType.TOOL_CALL, content.type());
        assertEquals("call-1", ((ConversationContent.ToolCallBlock) content).callId());
        assertEquals("ReadFile", ((ConversationContent.ToolCallBlock) content).toolName());
    }

    @Test
    void toolResultContent() {
        ToolResult result = ToolResult.success("call-1", "ReadFile", "文件内容");
        ToolResultContent trc = new ToolResultContent(result, "call-1", "ReadFile");
        ConversationContent content = new ConversationContent.ToolResultBlock(trc);
        assertEquals(ConversationContent.ContentType.TOOL_RESULT, content.type());
        assertEquals("call-1", ((ConversationContent.ToolResultBlock) content).callId());
        assertTrue(((ConversationContent.ToolResultBlock) content).toolResult().result().success());
    }

    @Test
    void fromSimpleMessages() {
        List<ConversationContent> contents = ConversationContent.fromSimpleMessages("你好", "回答");
        assertEquals(2, contents.size());
        assertEquals(ConversationContent.ContentType.USER_TEXT, contents.get(0).type());
        assertEquals(ConversationContent.ContentType.ASSISTANT_TEXT, contents.get(1).type());
    }

    @Test
    void structuredTurnPreservesOrder() {
        ToolCall call = new ToolCall("call-1", "ReadFile", Map.of("path", "test.txt"), "{\"path\":\"test.txt\"}", "anthropic");
        ToolResult result = ToolResult.success("call-1", "ReadFile", "内容");
        ToolResultContent trc = new ToolResultContent(result, "call-1", "ReadFile");

        List<ConversationContent> contents = List.of(
                new ConversationContent.UserText("读取文件"),
                new ConversationContent.AssistantText("好的"),
                new ConversationContent.ToolCallBlock(call),
                new ConversationContent.ToolResultBlock(trc),
                new ConversationContent.AssistantText("已完成")
        );

        ConversationTurn turn = new ConversationTurn(contents, null, null);
        assertEquals(5, turn.contents().size());
        assertTrue(turn.hasToolCalls());
        assertTrue(turn.hasToolResults());
        assertEquals("读取文件", turn.userMessage().text());
    }

    @Test
    void structuredTurnWithoutTools() {
        List<ConversationContent> contents = List.of(
                new ConversationContent.UserText("你好"),
                new ConversationContent.AssistantText("回答")
        );
        ConversationTurn turn = new ConversationTurn(contents, null, null);
        assertFalse(turn.hasToolCalls());
        assertFalse(turn.hasToolResults());
    }

    @Test
    void oldTextTurnConstructorStillWorks() {
        ConversationTurn turn = new ConversationTurn(
                new ChatMessage(Role.USER, "你好"),
                new ChatMessage(Role.ASSISTANT, "回答"),
                null, null);
        assertEquals(2, turn.contents().size());
        assertFalse(turn.hasToolCalls());
    }

    @Test
    void thinkingTextContent() {
        ConversationContent content = new ConversationContent.ThinkingText("思考中...");
        assertEquals(ConversationContent.ContentType.THINKING_TEXT, content.type());
    }

    @Test
    void toolCallPreservesArguments() {
        ToolCall call = new ToolCall("call-1", "ReadFile",
                Map.of("path", "test.txt", "start_line", 5),
                "{\"path\":\"test.txt\",\"start_line\":5}", "anthropic");
        assertEquals("test.txt", call.arguments().get("path"));
        assertEquals(5, call.arguments().get("start_line"));
    }

    @Test
    void assistantOnlyTurnHasNoUserMessage() {
        ToolCall call = new ToolCall("call-2", "ReadFile", Map.of("path", "a.txt"), "{\"path\":\"a.txt\"}", "anthropic");
        ToolResult result = ToolResult.success("call-2", "ReadFile", "内容");
        ToolResultContent trc = new ToolResultContent(result, "call-2", "ReadFile");
        List<ConversationContent> contents = List.of(
                new ConversationContent.ToolCallBlock(call),
                new ConversationContent.ToolResultBlock(trc)
        );
        ConversationTurn turn = new ConversationTurn(contents, null, Usage.unknown());
        assertNull(turn.userMessage(), "后续轮没有用户文本块时 userMessage 应为 null");
        assertFalse(turn.hasUserText());
        assertTrue(turn.hasToolCalls());
        assertTrue(turn.hasToolResults());
        assertEquals(2, turn.contents().size());
    }

    @Test
    void multiRoundHistoryPairsCallsAndResultsAcrossRounds() {
        ToolCall callA = new ToolCall("call-A", "ReadFile", Map.of("path", "a.txt"), "{\"path\":\"a.txt\"}", "anthropic");
        ToolResult resultA = ToolResult.success("call-A", "ReadFile", "A内容");
        ToolCall callB = new ToolCall("call-B", "Glob", Map.of("pattern", "**/*.java"), "{\"pattern\":\"**/*.java\"}", "anthropic");
        ToolResult resultB = ToolResult.success("call-B", "Glob", "B内容");

        ConversationTurn first = new ConversationTurn(List.of(
                new ConversationContent.UserText("查询"),
                new ConversationContent.ToolCallBlock(callA),
                new ConversationContent.ToolResultBlock(new ToolResultContent(resultA, "call-A", "ReadFile"))
        ), null, Usage.unknown());
        ConversationTurn second = new ConversationTurn(List.of(
                new ConversationContent.ToolCallBlock(callB),
                new ConversationContent.ToolResultBlock(new ToolResultContent(resultB, "call-B", "Glob")),
                new ConversationContent.AssistantText("完成")
        ), null, Usage.unknown());

        assertTrue(first.hasUserText());
        assertEquals("查询", first.userMessage().text());
        assertFalse(second.hasUserText(), "第二轮的调用和结果不得派生空用户消息");

        // 跨轮不得合并：第一轮调用 ID 与第二轮调用 ID 各自配对
        assertEquals("call-A", first.contents().stream()
                .filter(c -> c.type() == ConversationContent.ContentType.TOOL_CALL)
                .map(c -> ((ConversationContent.ToolCallBlock) c).callId()).findFirst().orElseThrow());
        assertEquals("call-B", second.contents().stream()
                .filter(c -> c.type() == ConversationContent.ContentType.TOOL_CALL)
                .map(c -> ((ConversationContent.ToolCallBlock) c).callId()).findFirst().orElseThrow());
        assertEquals(6, first.contents().size() + second.contents().size());
    }
}