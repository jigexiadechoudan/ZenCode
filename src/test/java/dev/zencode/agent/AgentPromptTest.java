package dev.zencode.agent;

import dev.zencode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T9：Agent 事件、运行选项和最小提示词测试。
 */
class AgentPromptTest {

    // ---- AgentRunOptions ----

    @Test
    void defaultsAreNormalModeWithTwentyRounds() {
        AgentRunOptions options = AgentRunOptions.defaults();
        assertFalse(options.planOnly());
        assertEquals(20, options.maxRounds());
        assertEquals(Duration.ofSeconds(120), options.responseIdleTimeout());
        assertEquals(64, options.eventBufferCapacity());
    }

    @Test
    void customOptionsPreserveFields() {
        AgentRunOptions options = new AgentRunOptions(true, 5, Duration.ofSeconds(10), 8);
        assertTrue(options.planOnly());
        assertEquals(5, options.maxRounds());
        assertEquals(Duration.ofSeconds(10), options.responseIdleTimeout());
        assertEquals(8, options.eventBufferCapacity());
    }

    @Test
    void rejectsNonPositiveRounds() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRunOptions(false, 0, Duration.ofSeconds(120), 64));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunOptions(false, -1, Duration.ofSeconds(120), 64));
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRunOptions(false, 20, Duration.ZERO, 64));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunOptions(false, 20, Duration.ofSeconds(-1), 64));
        assertThrows(NullPointerException.class, () -> new AgentRunOptions(false, 20, null, 64));
    }

    @Test
    void rejectsNonPositiveBufferCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRunOptions(false, 20, Duration.ofSeconds(120), 0));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunOptions(false, 20, Duration.ofSeconds(120), -5));
    }

    // ---- AgentTerminationReason ----

    @Test
    void hasFourTerminationReasons() {
        assertEquals(4, AgentTerminationReason.values().length);
        assertEquals(AgentTerminationReason.COMPLETED, AgentTerminationReason.valueOf("COMPLETED"));
        assertEquals(AgentTerminationReason.ROUND_LIMIT, AgentTerminationReason.valueOf("ROUND_LIMIT"));
        assertEquals(AgentTerminationReason.CANCELLED, AgentTerminationReason.valueOf("CANCELLED"));
        assertEquals(AgentTerminationReason.ERROR, AgentTerminationReason.valueOf("ERROR"));
    }

    // ---- AgentEvent ----

    @Test
    void userMessageCarriesText() {
        AgentEvent.UserMessage event = new AgentEvent.UserMessage("你好");
        assertEquals("你好", event.text());
        assertInstanceOf(AgentEvent.class, event);
    }

    @Test
    void thinkingAndTextDeltasCarryRound() {
        AgentEvent.ThinkingDelta thinking = new AgentEvent.ThinkingDelta("思考", 1);
        AgentEvent.TextDelta text = new AgentEvent.TextDelta("回复", 1);
        assertEquals("思考", thinking.text());
        assertEquals(1, thinking.round());
        assertEquals("回复", text.text());
        assertEquals(1, text.round());
    }

    @Test
    void toolEventsCarrySafeFields() {
        ToolResult result = ToolResult.success("call-1", "ReadFile", "内容");
        AgentEvent.ToolStarted started = new AgentEvent.ToolStarted("call-1", "ReadFile", "path=a.txt", 1);
        AgentEvent.ToolFinished finished = new AgentEvent.ToolFinished("call-1", "ReadFile", result, 1);
        assertEquals("call-1", started.callId());
        assertEquals("ReadFile", started.toolName());
        assertEquals("path=a.txt", started.safeSummary());
        assertEquals(result, finished.result());
        assertEquals(1, finished.round());
    }

    @Test
    void finalReplyAndErrorCarryText() {
        AgentEvent.FinalReply reply = new AgentEvent.FinalReply("最终答复");
        AgentEvent.Error error = new AgentEvent.Error("安全错误");
        assertEquals("最终答复", reply.text());
        assertEquals("安全错误", error.safeMessage());
    }

    @Test
    void terminatedCarriesReasonAndRounds() {
        AgentEvent.Terminated terminated = new AgentEvent.Terminated(AgentTerminationReason.COMPLETED, 3);
        assertEquals(AgentTerminationReason.COMPLETED, terminated.reason());
        assertEquals(3, terminated.completedRounds());
    }

    @Test
    void eventTypesAreSealed() {
        assertTrue(AgentEvent.class.isSealed());
        assertEquals(11, AgentEvent.class.getPermittedSubclasses().length);
    }
}
