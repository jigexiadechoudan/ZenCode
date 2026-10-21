package dev.zencode.app;

import dev.zencode.agent.AgentRun;
import dev.zencode.agent.AgentRunImpl;
import dev.zencode.agent.AgentRunOptions;
import dev.zencode.agent.AgentRunner;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.Usage;
import dev.zencode.hook.Hook;
import dev.zencode.hook.HookEngine;
import dev.zencode.hook.HookEvent;
import dev.zencode.hook.PromptAction;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.hitl.HitlRequest;
import dev.zencode.tui.ConversationView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8：ConversationController 首次提交触发 session_start、close 触发 session_end。
 */
class ConversationControllerHookTest {

    private static Hook promptHook(String id, HookEvent event, String text) {
        return new Hook(id, event, null, new PromptAction(text), false, false, false);
    }

    private static HookEngine sessionEngine() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(
                promptHook("s", HookEvent.SESSION_START, "[SESSION_START]"),
                promptHook("e", HookEvent.SESSION_END, "[SESSION_END]")));
        return engine;
    }

    @Test
    void firstSubmitTriggersSessionStart() {
        HookEngine engine = sessionEngine();
        ConversationController controller = new ConversationController(
                new Conversation(), new FakeAgent(), new NoopView());
        controller.setHookEngine(engine);

        assertTrue(controller.submit("你好"));
        assertTrue(engine.injectedPrompts().contains("[SESSION_START]"));

        controller.close();
        assertTrue(engine.injectedPrompts().contains("[SESSION_START]"), "session_start 应已触发");
        assertTrue(engine.injectedPrompts().contains("[SESSION_END]"), "close 应触发 session_end");
    }

    @Test
    void secondSubmitDoesNotReTriggerSessionStart() {
        HookEngine engine = sessionEngine();
        ConversationController controller = new ConversationController(
                new Conversation(), new FakeAgent(), new NoopView());
        controller.setHookEngine(engine);

        assertTrue(controller.submit("第一轮"));
        controller.submit("第二轮");
        assertEquals(1, engine.injectedPrompts().size(), "session_start 只触发一次");
    }

    @Test
    void closeWithoutSubmitDoesNotTriggerSessionEnd() {
        HookEngine engine = sessionEngine();
        ConversationController controller = new ConversationController(
                new Conversation(), new FakeAgent(), new NoopView());
        controller.setHookEngine(engine);

        controller.close();

        assertTrue(engine.injectedPrompts().isEmpty(), "未提交过会话不应触发任何 session 事件");
    }

    private static final class FakeAgent implements AgentRunner {
        @Override
        public AgentRun run(Conversation conversation, String userMessage, AgentRunOptions options) {
            return new AgentRunImpl(options.eventBufferCapacity());
        }
    }

    private static final class NoopView implements ConversationView {
        @Override public void showUserMessage(String s) {}
        @Override public void beginAssistantMessage() {}
        @Override public void appendThinking(String s) {}
        @Override public void appendAnswer(String s) {}
        @Override public void finishAssistantMessage() {}
        @Override public void markAssistantAborted() {}
        @Override public void showError(String s) {}
        @Override public void showUsage(Usage c, Usage t) {}
        @Override public void setBusy(boolean b) {}
        @Override public void showToolEvent(String type, String toolName, String callId, String summary) {}
        @Override public void showPermissionRequest(HitlRequest request) {}
        @Override public boolean resolvePermission(HitlDecision decision) { return false; }
    }
}
