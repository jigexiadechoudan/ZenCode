package dev.zencode.command;

import dev.zencode.agent.AgentEvent;
import dev.zencode.agent.AgentRun;
import dev.zencode.agent.AgentRunImpl;
import dev.zencode.agent.AgentRunOptions;
import dev.zencode.agent.AgentRunner;
import dev.zencode.agent.AgentTerminationReason;
import dev.zencode.app.ConversationController;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Usage;
import dev.zencode.context.ContextCompressor;
import dev.zencode.memory.MemoryConfig;
import dev.zencode.memory.MemoryStore;
import dev.zencode.permission.PermissionMode;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.hitl.HitlRequest;
import dev.zencode.session.SessionStore;
import dev.zencode.tui.ConversationView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class CommandUiAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void forwardsPlanAndPermission() {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view);
        CommandUiAdapter adapter = new CommandUiAdapter(controller, null);

        adapter.setPlanOnly(true);
        assertTrue(adapter.planOnly());
        assertEquals(PermissionMode.DEFAULT, adapter.permissionMode());
        adapter.switchPermissionMode(PermissionMode.LOCKED);
        assertEquals(PermissionMode.LOCKED, adapter.permissionMode());
    }

    @Test
    void forwardsSessionOps() {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        SessionStore store = new SessionStore(tempDir);
        dev.zencode.memory.SessionManager manager =
                new dev.zencode.memory.SessionManager(store, MemoryConfig.defaults(), null, conversation);
        ConversationController controller = new ConversationController(conversation, agent, view,
                AgentRunOptions.defaults(), new dev.zencode.permission.hitl.HitlBroker(),
                null, null, null, manager, null, MemoryConfig.defaults());
        CommandUiAdapter adapter = new CommandUiAdapter(controller, null);

        assertTrue(adapter.newSession(), "首次提交前应可新建会话");
        assertNotNull(adapter.currentSessionId());
        assertFalse(adapter.listSessions().isEmpty());
    }

    @Test
    void submitRunsThroughController() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view);
        CommandUiAdapter adapter = new CommandUiAdapter(controller, null);

        assertTrue(adapter.submit("任务 prompt"));
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("任务 prompt"));
        run.publish(new AgentEvent.FinalReply("完成"));
        run.publishTerminated(AgentTerminationReason.COMPLETED);
        awaitIdle(controller);
        assertFalse(controller.isBusy());
    }

    @Test
    void memoryOperationsWithNullStoreAreSafe() {
        ConversationController controller = new ConversationController(
                new Conversation(), new FakeAgent(), new RecordingView());
        CommandUiAdapter adapter = new CommandUiAdapter(controller, null);
        assertEquals("", adapter.memoryRead("PROJECT"));
        assertFalse(adapter.memoryClear("PROJECT"));
        assertEquals("", adapter.memoryFile("PROJECT"));
        assertEquals("", adapter.memoryRead("MERGED"));
        assertNull(adapter.currentSessionId());
    }

    @Test
    void memoryReadWriteWithStore() {
        MemoryStore store = new MemoryStore(tempDir, tempDir.resolve("home"), MemoryConfig.defaults());
        store.write(MemoryStore.Scope.PROJECT, "项目知识 A");
        ConversationController controller = new ConversationController(
                new Conversation(), new FakeAgent(), new RecordingView());
        CommandUiAdapter adapter = new CommandUiAdapter(controller, store);

        assertTrue(adapter.memoryRead("PROJECT").contains("项目知识 A"));
        assertTrue(adapter.memoryRead("MERGED").contains("项目知识 A"));
        assertTrue(adapter.memoryFile("PROJECT").endsWith("memories.md"));
        assertTrue(adapter.memoryClear("PROJECT"));
        assertEquals("", adapter.memoryRead("PROJECT"));
    }

    private static void awaitIdle(ConversationController controller) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (controller.isBusy() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(controller.isBusy());
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

    private static final class RecordingView implements ConversationView {
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