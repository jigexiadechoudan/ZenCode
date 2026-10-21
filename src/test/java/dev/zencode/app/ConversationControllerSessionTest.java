package dev.zencode.app;

import dev.zencode.agent.AgentEvent;
import dev.zencode.agent.AgentRun;
import dev.zencode.agent.AgentRunImpl;
import dev.zencode.agent.AgentRunOptions;
import dev.zencode.agent.AgentRunner;
import dev.zencode.agent.AgentTerminationReason;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Usage;
import dev.zencode.memory.MemoryConfig;
import dev.zencode.memory.MemoryExtractor;
import dev.zencode.memory.SessionManager;
import dev.zencode.permission.hitl.HitlBroker;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.hitl.HitlRequest;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.session.SessionMeta;
import dev.zencode.session.SessionStore;
import dev.zencode.session.SessionTurnDto;
import dev.zencode.tui.ConversationView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10：Controller 会话/记忆接线。验证首次提交启动会话、每轮提交后增量存档、
 * 会话命令（list/load/delete/new）行为、忙碌时命令被拒、close 兜底存档与提取。
 */
class ConversationControllerSessionTest {

    @TempDir
    Path tempDir;

    @Test
    void firstSubmitStartsSession() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        SessionStore store = new SessionStore(tempDir);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);
        ConversationController controller = controller(conversation, agent, view,
                manager, new MemoryExtractor(new RecordingProvider(), Duration.ofSeconds(5)),
                MemoryConfig.defaults());

        assertNull(controller.currentSessionId(), "未提交前无会话");
        assertTrue(controller.submit("第一轮"));

        String sessionId = controller.currentSessionId();
        assertNotNull(sessionId, "首次提交应启动会话");
        assertNotNull(store.readMeta(sessionId), "会话 meta 应写入磁盘");

        terminate(agent, view, controller);
    }

    @Test
    void roundCommitPersistsAndBumpsRound() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        SessionStore store = new SessionStore(tempDir);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);
        ConversationController controller = controller(conversation, agent, view,
                manager, new MemoryExtractor(new RecordingProvider(), Duration.ofSeconds(5)),
                MemoryConfig.defaults());

        assertTrue(controller.submit("任务"));
        String sessionId = controller.currentSessionId();

        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("任务"));
        conversation.commit(ConversationTurn.userTextOnly("任务"));
        run.publish(new AgentEvent.RoundUsageCommitted(1, usage()));
        run.publish(new AgentEvent.FinalReply("完成"));
        run.publishTerminated(AgentTerminationReason.COMPLETED);
        awaitIdle(controller);

        List<SessionTurnDto> turns = store.load(sessionId);
        assertFalse(turns.isEmpty(), "RoundUsageCommitted 后应增量存档");
        assertEquals(1, manager.roundCount(), "每轮提交后轮次计数递增");
        assertEquals(sessionId, controller.currentSessionId());
    }

    @Test
    void listSessionsReturnsArchivedSessions() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        SessionStore store = new SessionStore(tempDir);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);
        ConversationController controller = controller(conversation, agent, view,
                manager, new MemoryExtractor(new RecordingProvider(), Duration.ofSeconds(5)),
                MemoryConfig.defaults());

        assertTrue(controller.submit("会话A"));
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("会话A"));
        conversation.commit(ConversationTurn.userTextOnly("会话A"));
        run.publish(new AgentEvent.RoundUsageCommitted(1, usage()));
        run.publishTerminated(AgentTerminationReason.COMPLETED);
        awaitIdle(controller);

        assertTrue(controller.newSession(), "空闲时新建会话应成功");
        assertTrue(controller.submit("会话B"));
        run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("会话B"));
        conversation.commit(ConversationTurn.userTextOnly("会话B"));
        run.publish(new AgentEvent.RoundUsageCommitted(1, usage()));
        run.publishTerminated(AgentTerminationReason.COMPLETED);
        awaitIdle(controller);

        List<SessionMeta> sessions = controller.listSessions();
        assertEquals(2, sessions.size(), "应列出全部已存档会话");
        assertTrue(sessions.stream().allMatch(m -> m.messageCount() >= 1), "meta 应携带消息数");
    }

    @Test
    void loadSessionRestoresHistory() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        SessionStore store = new SessionStore(tempDir);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);
        ConversationController controller = controller(conversation, agent, view,
                manager, new MemoryExtractor(new RecordingProvider(), Duration.ofSeconds(5)),
                MemoryConfig.defaults());

        assertTrue(controller.submit("历史消息"));
        String idA = controller.currentSessionId();
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("历史消息"));
        conversation.commit(ConversationTurn.userTextOnly("历史消息"));
        run.publish(new AgentEvent.RoundUsageCommitted(1, usage()));
        run.publishTerminated(AgentTerminationReason.COMPLETED);
        awaitIdle(controller);

        assertTrue(controller.newSession());
        assertTrue(conversation.snapshot().isEmpty(), "新建会话后历史应清空");

        assertTrue(controller.loadSession(idA));
        assertEquals(idA, controller.currentSessionId());
        assertFalse(conversation.snapshot().isEmpty(), "恢复后对话历史应重建");
        assertEquals("历史消息", conversation.snapshot().get(0).userMessage().text());
    }

    @Test
    void deleteSessionRemovesOtherButNotCurrent() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        SessionStore store = new SessionStore(tempDir);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);
        ConversationController controller = controller(conversation, agent, view,
                manager, new MemoryExtractor(new RecordingProvider(), Duration.ofSeconds(5)),
                MemoryConfig.defaults());

        assertTrue(controller.submit("会话A"));
        String idA = controller.currentSessionId();
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("会话A"));
        conversation.commit(ConversationTurn.userTextOnly("会话A"));
        run.publish(new AgentEvent.RoundUsageCommitted(1, usage()));
        run.publishTerminated(AgentTerminationReason.COMPLETED);
        awaitIdle(controller);

        assertFalse(controller.deleteSession(idA), "删除当前活跃会话应被拒绝");
        assertEquals(1, controller.listSessions().size());

        assertTrue(controller.newSession());
        String idB = controller.currentSessionId();
        assertNotEquals(idA, idB, "新建会话应分配新 ID");

        assertTrue(controller.deleteSession(idA), "删除非当前会话应成功");
        assertEquals(1, controller.listSessions().size());
        assertFalse(controller.deleteSession(idB), "删除当前活跃会话应被拒绝");
    }

    @Test
    void busyRejectsSessionCommands() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        SessionStore store = new SessionStore(tempDir);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);
        ConversationController controller = controller(conversation, agent, view,
                manager, new MemoryExtractor(new RecordingProvider(), Duration.ofSeconds(5)),
                MemoryConfig.defaults());

        assertTrue(controller.submit("忙碌中"));
        assertTrue(controller.isBusy());
        assertFalse(controller.loadSession("任意id"), "忙碌时 load 应被拒绝");
        assertFalse(controller.deleteSession("任意id"), "忙碌时 delete 应被拒绝");
        assertFalse(controller.newSession(), "忙碌时 new 应被拒绝");
        assertFalse(controller.memoryExtract(), "忙碌时 extract 应被拒绝");

        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("忙碌中"));
        run.publishTerminated(AgentTerminationReason.COMPLETED);
        awaitIdle(controller);

        assertTrue(controller.newSession(), "空闲后 new 应恢复可用");
        assertFalse(controller.memoryExtract(), "新会话没有历史时不应报告已触发");
        conversation.commit(ConversationTurn.userTextOnly("待记忆内容"));
        assertTrue(controller.memoryExtract(), "空闲且有历史时 extract 应可用");
    }

    @Test
    void memoryExtractTriggersWhenIdle() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        RecordingProvider provider = new RecordingProvider();
        MemoryExtractor extractor = new MemoryExtractor(provider, Duration.ofSeconds(5));
        ConversationController controller = controller(conversation, agent, view,
                new SessionManager(new SessionStore(tempDir), MemoryConfig.defaults(), null, conversation),
                extractor, MemoryConfig.defaults());

        assertTrue(controller.submit("记录偏好"));
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("记录偏好"));
        conversation.commit(ConversationTurn.userTextOnly("记录偏好"));
        run.publish(new AgentEvent.RoundUsageCommitted(1, usage()));
        run.publish(new AgentEvent.FinalReply("已记录"));
        run.publishTerminated(AgentTerminationReason.COMPLETED);
        awaitIdle(controller);

        int before = provider.calls.get();
        assertTrue(controller.memoryExtract(), "空闲时应可手动触发提取");
        assertTrue(provider.calls.get() > before, "提取应发起后台 Provider 调用");
    }

    @Test
    void closeSavesPendingAndTriggersExtraction() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        RecordingProvider provider = new RecordingProvider();
        SessionStore store = new SessionStore(tempDir);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);
        ConversationController controller = controller(conversation, agent, view,
                manager, new MemoryExtractor(provider, Duration.ofSeconds(5)),
                MemoryConfig.defaults());

        assertTrue(controller.submit("退出前消息"));
        String sessionId = controller.currentSessionId();
        conversation.commit(ConversationTurn.userTextOnly("退出前消息"));

        int before = provider.calls.get();
        controller.close();

        assertFalse(store.load(sessionId).isEmpty(), "close 应兜底存档未提交轮次");
        assertTrue(provider.calls.get() > before, "close 应触发退出兜底记忆提取");
    }

    private ConversationController controller(Conversation conversation, FakeAgent agent,
                                              RecordingView view, SessionManager manager,
                                              MemoryExtractor extractor, MemoryConfig config) {
        ConversationController controller = new ConversationController(conversation, agent, view,
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64), new HitlBroker(),
                null, null, null, manager, extractor, config);
        controller.setMemoryStore(new dev.zencode.memory.MemoryStore(tempDir, tempDir.resolve("home"), config));
        return controller;
    }

    private static Usage usage() {
        return new Usage(10, 5, 0, 1, 4, 15, null, null, null, null);
    }

    private static void terminate(FakeAgent agent, RecordingView view,
                                  ConversationController controller) throws Exception {
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("第一轮"));
        run.publishTerminated(AgentTerminationReason.COMPLETED);
        awaitIdle(controller);
    }

    private static void awaitIdle(ConversationController controller) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (controller.isBusy() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(controller.isBusy(), "应在超时前释放 busy");
    }

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

    private static final class RecordingProvider implements ChatProvider {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
            calls.incrementAndGet();
            CompletableFuture<Void> completion = new CompletableFuture<>();
            consumer.accept(new StreamEvent.TextDelta("无分类内容"));
            consumer.accept(new StreamEvent.Completed(new ProviderResponse(
                    "", "", List.of(), ProviderStopReason.END_TURN, null, null)));
            completion.complete(null);
            return new StreamHandle() {
                @Override
                public void cancel() {
                    completion.complete(null);
                }

                @Override
                public CompletionStage<Void> completion() {
                    return completion;
                }
            };
        }

        @Override
        public String source() {
            return "test";
        }
    }

    private static final class RecordingView implements ConversationView {
        volatile boolean busy;

        @Override public void showUserMessage(String s) {}
        @Override public void beginAssistantMessage() {}
        @Override public void appendThinking(String s) {}
        @Override public void appendAnswer(String s) {}
        @Override public void finishAssistantMessage() {}
        @Override public void markAssistantAborted() {}
        @Override public void showError(String s) {}
        @Override public void showUsage(Usage c, Usage t) {}
        @Override public void setBusy(boolean b) { busy = b; }
        @Override public void showToolEvent(String type, String toolName, String callId, String summary) {}
        @Override public void showPermissionRequest(HitlRequest request) {}
        @Override public boolean resolvePermission(HitlDecision decision) { return false; }
    }
}
