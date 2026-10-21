package dev.zencode.agent;

import dev.zencode.chat.Conversation;
import dev.zencode.chat.Usage;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.tool.BaseTool;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;
import dev.zencode.provider.ToolCallAssembler.AssembledCall;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** AC1/AC4：每轮提交后发布 RoundUsageCommitted，位于 Terminated 之前，Terminated 仍唯一。 */
class AgentRoundUsageFlowTest {

    @Test
    void publishesPerRoundUsageBeforeTermination() throws Exception {
        ScriptedProvider provider = new ScriptedProvider();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("read", ToolCategory.READ));
        Agent agent = new Agent(provider, registry, Path.of("."));
        AgentRunOptions options = timeout(3);

        AgentRun run = agent.run(new Conversation(), "多轮", options);
        List<AgentEvent> events = drain(run);

        List<AgentEvent.RoundUsageCommitted> committed = allOf(events, AgentEvent.RoundUsageCommitted.class);
        assertEquals(3, committed.size(), "三轮各应发布一条用量事件");
        assertEquals(1, committed.get(0).round());
        assertEquals(2, committed.get(1).round());
        assertEquals(3, committed.get(2).round());

        assertEquals(11, committed.get(0).usage().inputTokens());
        assertEquals(21, committed.get(0).usage().outputTokens());
        assertEquals(2, committed.get(0).usage().cacheReadTokens());

        int firstTerminal = indexOf(events, e -> e instanceof AgentEvent.Terminated);
        int lastCommitted = lastIndexOf(events, e -> e instanceof AgentEvent.RoundUsageCommitted);
        assertTrue(lastCommitted < firstTerminal, "所有用量事件应早于唯一终止事件");
        assertEquals(1, allOf(events, AgentEvent.Terminated.class).size(), "Terminated 应仍唯一");
    }

    private static AgentRunOptions timeout(int rounds) {
        return new AgentRunOptions(false, rounds, Duration.ofSeconds(3), 64);
    }

    private static List<AgentEvent> drain(AgentRun run) throws Exception {
        List<AgentEvent> events = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            AgentEvent event = run.pollEvent(Duration.ofMillis(400));
            if (event == null) continue;
            events.add(event);
            if (event instanceof AgentEvent.Terminated) return events;
        }
        throw new AssertionError("Agent 没有在期限内终止");
    }

    private static <T extends AgentEvent> List<T> allOf(List<AgentEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static int indexOf(List<AgentEvent> events, java.util.function.Predicate<AgentEvent> p) {
        for (int i = 0; i < events.size(); i++) {
            if (p.test(events.get(i))) return i;
        }
        return -1;
    }

    private static int lastIndexOf(List<AgentEvent> events, java.util.function.Predicate<AgentEvent> p) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (p.test(events.get(i))) return i;
        }
        return -1;
    }

    private static Usage usage(int input, int output, int cached) {
        return new Usage(input, output, 0, 1, cached, input + output, null, null, "end_turn", "req");
    }

    private static final class StubTool extends BaseTool {
        StubTool(String name, ToolCategory category) {
            super(name, "测试工具 " + name, category, ToolPermission.READ_ONLY, Map.of());
        }

        @Override
        protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
            return ToolResult.success(ctx.callId(), name(), "ok");
        }
    }

    private static final class ScriptedProvider implements ChatProvider {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
            java.util.concurrent.CompletableFuture<Void> completion =
                    new java.util.concurrent.CompletableFuture<>();
            int round = calls.incrementAndGet();
            Usage u = usage(10 + round, 20 + round, round * 2);
            if (round < 3) {
                consumer.accept(new StreamEvent.Completed(new ProviderResponse("", "",
                        List.of(new AssembledCall(new ToolCall("c" + round, "read", Map.of(), "{}", "anthropic"), null)),
                        ProviderStopReason.TOOL_USE, u, null)));
            } else {
                consumer.accept(new StreamEvent.Completed(new ProviderResponse("", "完成",
                        List.of(), ProviderStopReason.END_TURN, u, null)));
            }
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