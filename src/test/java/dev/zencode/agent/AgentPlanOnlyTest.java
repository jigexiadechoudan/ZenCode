package dev.zencode.agent;

import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.Usage;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.provider.ToolCallAssembler.AssembledCall;
import dev.zencode.tool.BaseTool;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T17：plan-only 闭环测试。断言 Schema 未隐藏工具、仅只读工具产生执行副作用、
 * 写与命令工具返回关联原调用 ID 的 RESTRICTED 结果、三项结果正确配对写入历史，
 * 最终计划通过 FinalReply 发布。
 */
class AgentPlanOnlyTest {

    @Test
    void planOnlyRejectsWritesAndCommandsWhileAllowingReads() throws Exception {
        SideEffectTool read = new SideEffectTool("ReadFile", ToolCategory.READ);
        SideEffectTool write = new SideEffectTool("WriteFile", ToolCategory.WRITE);
        SideEffectTool bash = new SideEffectTool("Bash", ToolCategory.COMMAND);
        ToolRegistry registry = new ToolRegistry();
        registry.register(read);
        registry.register(write);
        registry.register(bash);

        ScriptedProvider provider = new ScriptedProvider(List.of(
                round -> {
                    round.emit(completedWithTools(List.of(
                            okCall("c1", "ReadFile"),
                            okCall("c2", "WriteFile"),
                            okCall("c3", "Bash"))));
                    round.completion.complete(null);
                },
                round -> {
                    round.emit(completed("实施计划：修改 x，读取 y。"));
                    round.completion.complete(null);
                }
        ));
        Agent agent = new Agent(provider, registry, Path.of("."));
        Conversation conversation = new Conversation();
        AgentRunOptions options = new AgentRunOptions(true, 20, Duration.ofSeconds(2), 64);

        List<AgentEvent> events = runAndDrain(agent, conversation, "调研并出计划", options);

        // Schema 未隐藏工具
        ProviderRequest first = provider.requests.get(0);
        List<String> schemaNames = first.toolSchemas().stream()
                .map(schema -> (String) schema.get("name"))
                .toList();
        assertEquals(List.of("ReadFile", "WriteFile", "Bash"), schemaNames, "plan-only 不应隐藏工具 Schema");

        // 仅只读工具产生执行副作用
        assertEquals(1, read.executions.get(), "只读工具应执行");
        assertEquals(0, write.executions.get(), "写工具不应执行");
        assertEquals(0, bash.executions.get(), "命令工具不应执行");

        // 写与命令工具返回关联原调用 ID 的 RESTRICTED 结果
        List<AgentEvent.ToolFinished> finished = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolFinished)
                .map(e -> (AgentEvent.ToolFinished) e)
                .toList();
        assertEquals(3, finished.size());
        ToolResult writeResult = finished.get(1).result();
        assertEquals(ToolResult.Status.RESTRICTED, writeResult.status());
        assertEquals("c2", writeResult.callId());
        assertEquals("WriteFile", writeResult.toolName());
        ToolResult bashResult = finished.get(2).result();
        assertEquals(ToolResult.Status.RESTRICTED, bashResult.status());
        assertEquals("c3", bashResult.callId());
        assertEquals("Bash", bashResult.toolName());

        // 三项结果按原顺序配对写入历史
        List<ConversationContent.ToolResultBlock> firstRound = conversation.snapshot().get(1).contents().stream()
                .filter(c -> c instanceof ConversationContent.ToolResultBlock)
                .map(c -> (ConversationContent.ToolResultBlock) c)
                .toList();
        assertEquals(List.of("c1", "c2", "c3"), firstRound.stream()
                .map(ConversationContent.ToolResultBlock::callId).toList());
        assertTrue(firstRound.get(0).toolResult().result().success());
        assertEquals(ToolResult.Status.RESTRICTED, firstRound.get(1).toolResult().result().status());
        assertEquals(ToolResult.Status.RESTRICTED, firstRound.get(2).toolResult().result().status());

        // 第二轮返回计划，通过 FinalReply 发布
        assertEquals(2, provider.requests.size(), "受限结果回填后可发起下一轮");
        assertEquals("实施计划：修改 x，读取 y。", firstOf(events, AgentEvent.FinalReply.class).text());
        assertEquals(AgentTerminationReason.COMPLETED, firstOf(events, AgentEvent.Terminated.class).reason());
        assertExactlyOneTermination(events);
    }

    // ---- 测试工具 ----

    private static List<AgentEvent> runAndDrain(Agent agent, Conversation conversation,
                                                String message, AgentRunOptions options) throws Exception {
        AgentRun run = agent.run(conversation, message, options);
        List<AgentEvent> events = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            AgentEvent event = run.pollEvent(Duration.ofMillis(500));
            if (event == null) {
                if (run.completion().isDone()) {
                    events.add(run.takeEvent());
                    break;
                }
                continue;
            }
            events.add(event);
            if (event instanceof AgentEvent.Terminated) break;
        }
        assertNotNull(firstOf(events, AgentEvent.Terminated.class), "应在超时前收到唯一终止事件");
        return events;
    }

    private static StreamEvent completed(String text) {
        return new StreamEvent.Completed(
                new ProviderResponse("", text, List.of(), ProviderStopReason.END_TURN, Usage.unknown(), null));
    }

    private static StreamEvent completedWithTools(List<AssembledCall> calls) {
        return new StreamEvent.Completed(
                new ProviderResponse("", "", calls, ProviderStopReason.TOOL_USE, Usage.unknown(), null));
    }

    private static AssembledCall okCall(String id, String name) {
        return new AssembledCall(new ToolCall(id, name, Map.of(), "{}", "anthropic"), null);
    }

    private static <T extends AgentEvent> T firstOf(List<AgentEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    private static void assertExactlyOneTermination(List<AgentEvent> events) {
        assertEquals(1, events.stream().filter(e -> e instanceof AgentEvent.Terminated).count());
    }

    private static final class SideEffectTool extends BaseTool {
        final AtomicInteger executions = new AtomicInteger();

        SideEffectTool(String name, ToolCategory category) {
            super(name, "副作用测试工具 " + name, category, ToolPermission.READ_ONLY, Map.of());
        }

        @Override
        protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
            executions.incrementAndGet();
            return ToolResult.success(ctx.callId(), name(), "ok-" + name());
        }
    }

    @FunctionalInterface
    private interface Round {
        void play(EmittingContext ctx);
    }

    private static final class EmittingContext {
        final Consumer<StreamEvent> emit;
        final CompletableFuture<Void> completion;

        EmittingContext(Consumer<StreamEvent> emit, CompletableFuture<Void> completion) {
            this.emit = emit;
            this.completion = completion;
        }

        void emit(StreamEvent event) {
            emit.accept(event);
        }
    }

    private static final class ScriptedProvider implements ChatProvider {
        final List<Round> rounds;
        final List<ProviderRequest> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger requestCount = new AtomicInteger();

        ScriptedProvider(List<Round> rounds) {
            this.rounds = rounds;
        }

        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
            requests.add(request);
            int index = requestCount.getAndIncrement();
            Round round = rounds.get(Math.min(index, rounds.size() - 1));
            CompletableFuture<Void> completion = new CompletableFuture<>();
            round.play(new EmittingContext(consumer, completion));
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
