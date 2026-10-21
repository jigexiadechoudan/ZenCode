package dev.zencode.session;

import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Usage;
import dev.zencode.chat.SeedType;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolResultContent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionTurnDtoTest {

    @Test
    void roundTripSimpleUserAssistantTurn() {
        ConversationTurn turn = ConversationTurn.userTextOnly("你好");
        SessionTurnDto dto = SessionTurnDto.fromTurn(turn);
        String json = dto.toJson();
        assertTrue(json.contains("\"t\":\"user\""), "JSON 应包含 user 类型");
        assertTrue(json.contains("你好"), "JSON 应包含文本");

        SessionTurnDto restored = SessionTurnDto.fromJson(json);
        ConversationTurn recovered = restored.toTurn();
        assertEquals(1, recovered.contents().size());
        assertInstanceOf(ConversationContent.UserText.class, recovered.contents().get(0));
        assertEquals("你好", ((ConversationContent.UserText) recovered.contents().get(0)).text());
        assertNull(recovered.providerState());
    }

    @Test
    void roundTripAllContentTypes() {
        ConversationTurn turn = new ConversationTurn(List.of(
                new ConversationContent.UserText("用户消息"),
                new ConversationContent.AssistantText("助手文本"),
                new ConversationContent.ThinkingText("思考内容"),
                new ConversationContent.ToolCallBlock(new ToolCall("call-1", "read_file",
                        Map.of("path", "/test.txt"), "{\"path\":\"/test.txt\"}", "anthropic")),
                new ConversationContent.ToolResultBlock(new ToolResultContent(
                        ToolResult.success("call-1", "read_file", "文件内容"), "call-1", "read_file"))
        ), null, null);

        String json = SessionTurnDto.fromTurn(turn).toJson();
        assertTrue(json.contains("\"t\":\"user\""));
        assertTrue(json.contains("\"t\":\"assistant\""));
        assertTrue(json.contains("\"t\":\"thinking\""));
        assertTrue(json.contains("\"t\":\"tool_call\""));
        assertTrue(json.contains("\"t\":\"tool_result\""));
        assertTrue(json.contains("call-1"));

        SessionTurnDto restored = SessionTurnDto.fromJson(json);
        ConversationTurn recovered = restored.toTurn();
        assertEquals(5, recovered.contents().size());
        assertEquals(ConversationContent.ContentType.USER_TEXT, recovered.contents().get(0).type());
        assertEquals(ConversationContent.ContentType.ASSISTANT_TEXT, recovered.contents().get(1).type());
        assertEquals(ConversationContent.ContentType.THINKING_TEXT, recovered.contents().get(2).type());
        assertEquals(ConversationContent.ContentType.TOOL_CALL, recovered.contents().get(3).type());
        assertEquals(ConversationContent.ContentType.TOOL_RESULT, recovered.contents().get(4).type());
    }

    @Test
    void roundTripUsage() {
        Usage usage = new Usage(10, 20, 5, 1, 2, 35,
                Duration.ofMillis(1000), Duration.ofMillis(500), "stop", "req-1");
        ConversationTurn turn = new ConversationTurn(
                List.of(new ConversationContent.UserText("hi"), new ConversationContent.AssistantText("hello")),
                null, usage);

        String json = SessionTurnDto.fromTurn(turn).toJson();
        assertTrue(json.contains("inputTokens"));
        assertTrue(json.contains("outputTokens"));

        SessionTurnDto restored = SessionTurnDto.fromJson(json);
        ConversationTurn recovered = restored.toTurn();
        Usage u = recovered.usage();
        assertNotNull(u);
        assertEquals(10, u.inputTokens());
        assertEquals(20, u.outputTokens());
        assertEquals(1000, u.duration().toMillis());
        assertEquals("stop", u.finishReason());
    }

    @Test
    void versionFieldIsOne() {
        SessionTurnDto dto = SessionTurnDto.fromTurn(ConversationTurn.userTextOnly("test"));
        String json = dto.toJson();
        assertTrue(json.contains("\"v\":1"));
    }

    @Test
    void providerStateNotPersisted() {
        ConversationTurn turn = new ConversationTurn(
                List.of(new ConversationContent.UserText("hi"), new ConversationContent.AssistantText("bye")),
                null, null);
        String json = SessionTurnDto.fromTurn(turn).toJson();
        SessionTurnDto restored = SessionTurnDto.fromJson(json);
        ConversationTurn recovered = restored.toTurn();
        assertNull(recovered.providerState());
    }

    @Test
    void typedSeedSurvivesSessionArchiveAndRestore() {
        ConversationTurn seed = ConversationTurn.seedTextOnly(
                SeedType.PROJECT_INSTRUCTIONS, "必须遵守项目约束");
        ConversationTurn restored = SessionTurnDto.fromJson(
                SessionTurnDto.fromTurn(seed).toJson()).toTurn();
        assertEquals(SeedType.PROJECT_INSTRUCTIONS, restored.seedType());
        assertEquals("必须遵守项目约束", restored.userMessage().text());
    }
}
