package dev.zencode.agent;

import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Usage;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.tool.BaseTool;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** T16/AgentReminderFlow：Plan Mode reminder 以轮次级瞬时通道注入，不污染持久历史。 */
class AgentReminderFlowTest {

    private static AgentRunOptions options() {
        return new AgentRunOptions(true, 3, Duration.ofSeconds(3), 64);
    }

    @Test
    void planModeSendsTransientRemindersButDoesNotPersistThemIntoHistory() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("grep", ToolCategory.READ));
        Agent agent = new Agent(provider, registry, Path.of("."));
        Conversation conversation = new Conversation();
        AgentRunOptions opts = options();

        AgentRun run = agent.run(conversation, "调研", opts);
        drainWait(run);
        List<ProviderRequest> requests = provider.requests;

        assertFalse(requests.isEmpty(), "应发生至少一次请求");
        ProviderRequest first = requests.get(0);
        assertTrue(first.hasTransientReminders(), "首轮 Plan Mode 应带完整 reminder");
        assertEquals(1, first.transientReminders().size());
        assertTrue(first.transientReminders().get(0).text().contains("<system-reminder>"));

        for (ProviderRequest req : requests) {
            for (ConversationTurn turn : req.history()) {
                // 历史均为真实提交轮次（环境 + 工具轮），不应含有注入的 Plan reminder 标签
                if (turn.userMessage() != null) {
                    assertFalse(turn.userMessage().text().contains("计划模式"),
                            "瞬时 Plan reminder 不应写入会话历史");
                }
            }
        }
    }

    @Test
    void planOnlyRoundUsesConciseWhenBeyondFirst() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        // 第一轮返回只读工具调用以触发第二轮
        provider.script(new StreamEvent.Completed(new ProviderResponse("", "",
                List.of(new dev.zencode.provider.ToolCallAssembler.AssembledCall(
                        new dev.zencode.tool.ToolCall("c1", "grep", Map.of(), "{}", "anthropic"), null)),
                ProviderStopReason.TOOL_USE, Usage.unknown(), null)));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("grep", ToolCategory.READ));
        Agent agent = new Agent(provider, registry, Path.of("."));
        AgentRun run = agent.run(new Conversation(), "第二轮", options());
        drainWait(run);

        assertTrue(provider.requests.size() >= 2, "应发生第二轮只读查询");
        ProviderRequest second = provider.requests.get(1);
        assertTrue(second.hasTransientReminders());
        assertEquals(1, second.transientReminders().size());
        assertFalse(second.transientReminders().get(0).text().contains("你当前处于计划模式"),
                "首轮之后应为精简版 reminder");
    }

    private static void drain(AgentRun run) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            AgentEvent e = run.pollEvent(Duration.ofMillis(200));
            if (e instanceof AgentEvent.Terminated) return;
        }
        throw new AssertionError("Agent 没有在期限内终止");
    }

    private static void drainWait(AgentRun run) throws Exception {
        drain(run);
    }

    private static class StubTool extends BaseTool {
        StubTool(String name, ToolCategory category) {
            super(name, "测试工具 " + name, category, ToolPermission.READ_ONLY, Map.of());
        }

        @Override
        protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
            return ToolResult.success(ctx.callId(), name(), "ok");
        }
    }

    private static final class RecordingProvider implements ChatProvider {
        final List<ProviderRequest> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger count = new AtomicInteger();
        volatile StreamEvent scriptedFirst;

        void script(StreamEvent event) {
            this.scriptedFirst = event;
        }

        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
            requests.add(request);
            java.util.concurrent.CompletableFuture<Void> completion = new java.util.concurrent.CompletableFuture<>();
            int round = count.getAndIncrement();
            StreamEvent event = round == 0 && scriptedFirst != null
                    ? scriptedFirst
                    : new StreamEvent.Completed(new ProviderResponse("", "完成",
                            List.of(), ProviderStopReason.END_TURN, Usage.unknown(), null));
            consumer.accept(event);
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
            return "anthropic";
        }
    }
}