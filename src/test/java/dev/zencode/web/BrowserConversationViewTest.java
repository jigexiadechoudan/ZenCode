package dev.zencode.web;

import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Usage;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolResultContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 历史恢复必须保持思考与正文分离，并且不向浏览器暴露工具内容。 */
class BrowserConversationViewTest {

    @Test void cancellationFinishesOpenStepsAndCallIdsAreScopedToEachTask() {
        BrowserConversationView view = new BrowserConversationView(() -> null);
        view.setBusy(true);
        view.showTraceEvent("model", "running", "请求模型", "", 1);
        view.showToolEvent("tool_start", "ReadFile", "same-call", "path=example.txt", 1);
        view.showTraceEvent("run", "cancelled", "任务结束", "", 0);
        view.setBusy(false);
        var first = view.snapshot().activities();
        assertTrue(first.stream().allMatch(item -> item.containsKey("finishedAt")));
        assertTrue(first.stream().filter(item -> "run".equals(item.get("phase")))
                .allMatch(item -> "cancelled".equals(item.get("state"))));
        view.setBusy(true);
        view.showToolEvent("tool_start", "ReadFile", "same-call", "path=other.txt", 1);
        view.showToolEvent("tool_completed", "ReadFile", "same-call", "成功", 1);
        view.setBusy(false);
        var tools = view.snapshot().activities().stream().filter(item -> "tool".equals(item.get("phase"))).toList();
        assertEquals(2, tools.size());
        assertNotEquals(tools.get(0).get("runId"), tools.get(1).get("runId"));
        assertEquals("path=other.txt", tools.get(1).get("requestSummary"));
        assertEquals("成功", tools.get(1).get("resultSummary"));
    }

    @Test
    void historySeparatesThinkingFromAnswerAndHidesToolContent() {
        ToolCall call = new ToolCall("call-1", "read_file", Map.of("path", "secret.txt"),
                "{\"path\":\"secret.txt\"}", "openai");
        ToolResult result = ToolResult.success("call-1", "read_file", "SECRET-TOOL-OUTPUT");
        ConversationTurn turn = new ConversationTurn(List.of(
                new ConversationContent.UserText("问题"),
                new ConversationContent.ThinkingText("内部推理"),
                new ConversationContent.AssistantText("正式回答"),
                new ConversationContent.ToolCallBlock(call),
                new ConversationContent.ToolResultBlock(new ToolResultContent(result, "call-1", "read_file"))
        ), null, Usage.unknown());

        BrowserConversationView view = new BrowserConversationView(() -> null);
        view.showHistory(List.of(turn));

        BrowserConversationView.UiState state = view.snapshot();
        assertEquals(2, state.messages().size());
        BrowserConversationView.UiMessage user = state.messages().get(0);
        assertEquals("user", user.role());
        assertEquals("问题", user.text());
        BrowserConversationView.UiMessage assistant = state.messages().get(1);
        assertEquals("assistant", assistant.role());
        assertEquals("内部推理", assistant.thinking());
        assertEquals("正式回答", assistant.text());
        assertFalse(assistant.text().contains("【思考】"));
        assertFalse(assistant.text().contains("SECRET-TOOL-OUTPUT"));
    }

    @Test
    void toolOnlyHistoryDoesNotCreateAnEmptyAssistantBubble() {
        ToolCall call = new ToolCall("call-1", "read_file", Map.of("path", "a.txt"),
                "{\"path\":\"a.txt\"}", "openai");
        ConversationTurn turn = new ConversationTurn(List.of(
                new ConversationContent.UserText("读取"),
                new ConversationContent.ToolCallBlock(call)
        ), null, Usage.unknown());

        BrowserConversationView view = new BrowserConversationView(() -> null);
        view.showHistory(List.of(turn));

        assertEquals(1, view.snapshot().messages().size());
        assertEquals("user", view.snapshot().messages().get(0).role());
    }
}
