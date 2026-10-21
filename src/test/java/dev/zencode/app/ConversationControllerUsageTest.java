package dev.zencode.app;

import dev.zencode.agent.AgentEvent;
import dev.zencode.agent.AgentRun;
import dev.zencode.agent.AgentRunImpl;
import dev.zencode.agent.AgentRunOptions;
import dev.zencode.agent.AgentRunner;
import dev.zencode.agent.AgentTerminationReason;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Usage;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.hitl.HitlRequest;
import dev.zencode.tui.ConversationView;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** AC2：每轮 RoundUsageCommitted 触发 showUsage，current 为本轮、cumulative 随轮累计正确。 */
class ConversationControllerUsageTest {

    @Test
    void roundUsageRefreshesViewWithCurrentAndCumulative() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view,
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64));

        // 轮 1 已提交（含 usage：input 10, output 5, cacheRead 4）
        conversation.commit(turn(10, 5, 4));
        Usage u1 = new Usage(10, 5, 0, 1, 4, 15, null, null, "end_turn", null);

        assertTrue(controller.submit("多轮"));
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("多轮"));
        run.publish(new AgentEvent.RoundUsageCommitted(1, u1));
        // 等待轮 1 快照落地，确保 cumulative 基于此刻仅包含轮 1 的历史
        awaitSnapshots(view, 1, 5000);

        // 轮 2 已提交
        conversation.commit(turn(20, 8, 9));
        run.publish(new AgentEvent.RoundUsageCommitted(2, new Usage(20, 8, 0, 2, 9, 28, null, null, "end_turn", null)));

        run.publish(new AgentEvent.FinalReply("完成"));
        run.publishTerminated(AgentTerminationReason.COMPLETED);
        awaitIdle(controller);

        assertEquals(3, view.usageSnapshots.size(), "两次每轮刷新 + 一次终止时最终刷新");
        // 第一次：current=轮1，cumulative=轮1（input10 output5 cacheRead4）
        UsageSnapshot s1 = view.usageSnapshots.get(0);
        assertEquals(10, s1.current().inputTokens());
        assertEquals(4, s1.current().cacheReadTokens());
        assertEquals(10, s1.cumulative().inputTokens());
        assertEquals(4, s1.cumulative().cacheReadTokens());

        // 第二次：current = 轮2（20/8），cumulative 累计 = 30/13、cacheRead 12
        UsageSnapshot s2 = view.usageSnapshots.get(1);
        assertEquals(20, s2.current().inputTokens());
        assertEquals(9, s2.current().cacheReadTokens());
        assertEquals(30, s2.cumulative().inputTokens());
        assertEquals(13, s2.cumulative().outputTokens());
        assertEquals(13, s2.cumulative().cacheReadTokens());
    }

    private static ConversationTurn turn(int input, int output, int cacheRead) {
        ConversationContent.UserText userText = new ConversationContent.UserText("任务");
        return new ConversationTurn(List.of(userText), null, new Usage(input, output, 0, 1, cacheRead, input + output, null, null, null, null));
    }

    private static void awaitIdle(ConversationController controller) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (controller.isBusy() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(controller.isBusy());
    }

    private static void awaitSnapshots(RecordingView view, int expected, long millis) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(millis).toNanos();
        while (view.usageSnapshots.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(view.usageSnapshots.size() >= expected, "应在超时前收到第 " + expected + " 个用量快照");
    }

    record UsageSnapshot(Usage current, Usage cumulative) {}

    private static final class FakeAgent implements AgentRunner {
        final CopyOnWriteArrayList<AgentRunImpl> runs = new CopyOnWriteArrayList<>();

        @Override
        public AgentRun run(Conversation conversation, String userMessage, AgentRunOptions options) {
            AgentRunImpl run = new AgentRunImpl(options.eventBufferCapacity());
            runs.add(run);
            return run;
        }

        AgentRunImpl lastRun() {
            return runs.get(runs.size() - 1);
        }
    }

    private static final class RecordingView implements ConversationView {
        final List<UsageSnapshot> usageSnapshots = new CopyOnWriteArrayList<>();
        volatile boolean busy;

        @Override public void showUserMessage(String s) {}
        @Override public void beginAssistantMessage() {}
        @Override public void appendThinking(String s) {}
        @Override public void appendAnswer(String s) {}
        @Override public void finishAssistantMessage() {}
        @Override public void markAssistantAborted() {}
        @Override public void showError(String s) {}
        @Override public void showUsage(Usage c, Usage t) { usageSnapshots.add(new UsageSnapshot(c, t)); }
        @Override public void setBusy(boolean b) { busy = b; }
        @Override public void showToolEvent(String type, String toolName, String callId, String summary) {}
        @Override public void showPermissionRequest(HitlRequest request) {}
        @Override public boolean resolvePermission(HitlDecision decision) { return false; }
    }
}