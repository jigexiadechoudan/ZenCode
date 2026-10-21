package dev.zencode.agent;

import dev.zencode.agent.execution.ToolInterceptorChain;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T13/T14/T15：Agent 主循环测试。覆盖单轮响应聚合与静默超时、
 * ReAct 多轮状态机与历史提交、轮数上限与唯一终止。
 */
class AgentLoopTest {

    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(8);

    // ---- T13：单轮响应聚合与静默超时 ----

    @Test
    void forwardsDeltasAndPublishesFinalReplyOnCompletion() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(new StreamEvent.ThinkingDelta("思考中"));
            round.emit(new StreamEvent.TextDelta("第"));
            round.emit(new StreamEvent.TextDelta("一段"));
            round.emit(completed("最终答复"));
            round.completion.complete(null);
        }));
        Agent agent = new Agent(provider, newRegistry(), Path.of("."));
        Conversation conversation = new Conversation();

        List<AgentEvent> events = runAndDrain(agent, conversation, "你好", timeout(2000));

        AgentEvent.UserMessage user = firstOf(events, AgentEvent.UserMessage.class);
        assertNotNull(user);
        assertEquals("你好", user.text());
        List<AgentEvent.TextDelta> deltas = allOf(events, AgentEvent.TextDelta.class);
        assertEquals("第" + "一段", deltas.stream().map(AgentEvent.TextDelta::text).reduce("", String::concat));
        assertTrue(deltas.stream().allMatch(d -> d.round() == 1));
        assertEquals("最终答复", firstOf(events, AgentEvent.FinalReply.class).text());
        assertEquals(AgentTerminationReason.COMPLETED, firstOf(events, AgentEvent.Terminated.class).reason());
        assertExactlyOneTermination(events);
        assertEquals(2, conversation.size(), "含环境首轮 + 文本轮");
    }

    @Test
    void silentTimeoutCancelsHandleAndPublishesSingleError() throws Exception {
        AtomicInteger cancelCalls = new AtomicInteger();
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(new StreamEvent.TextDelta("部分"));
            // 不再发送任何事件，触发静默超时
        }));
        provider.cancelCounter = cancelCalls;
        Agent agent = new Agent(provider, newRegistry(), Path.of("."));
        Conversation conversation = new Conversation();

        List<AgentEvent> events = runAndDrain(agent, conversation, "超时请求", timeout(150));

        assertEquals(1, cancelCalls.get(), "超时后应取消活动流句柄");
        AgentEvent.Error error = firstOf(events, AgentEvent.Error.class);
        assertNotNull(error);
        assertTrue(error.safeMessage().contains("超时"));
        AgentEvent.Terminated terminated = firstOf(events, AgentEvent.Terminated.class);
        assertEquals(AgentTerminationReason.ERROR, terminated.reason());
        assertEquals(0, conversation.size() - 1, "超时仅提交环境首轮，不提交历史轮");
        assertExactlyOneTermination(events);
    }

    @Test
    void providerCompletionWithoutTerminalPublishesProtocolError() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(new StreamEvent.TextDelta("部分"));
            round.completion.complete(null); // 完成但没有终端事件
        }));
        Agent agent = new Agent(provider, newRegistry(), Path.of("."));
        Conversation conversation = new Conversation();

        List<AgentEvent> events = runAndDrain(agent, conversation, "协议错误请求", timeout(2000));

        AgentEvent.Error error = firstOf(events, AgentEvent.Error.class);
        assertNotNull(error);
        assertTrue(error.safeMessage().contains("终端事件"));
        assertEquals(AgentTerminationReason.ERROR, firstOf(events, AgentEvent.Terminated.class).reason());
        assertExactlyOneTermination(events);
    }

    @Test
    void failureThenLateCompletionPublishesSingleError() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(new StreamEvent.Failed("模型失败"));
            round.emit(completed("迟到的完成")); // 迟到完成应被忽略
            round.completion.complete(null);
        }));
        Agent agent = new Agent(provider, newRegistry(), Path.of("."));
        Conversation conversation = new Conversation();

        List<AgentEvent> events = runAndDrain(agent, conversation, "失败请求", timeout(2000));

        assertEquals(1, allOf(events, AgentEvent.Error.class).size());
        assertNull(firstOf(events, AgentEvent.FinalReply.class), "失败后不发布最终回复");
        assertEquals(AgentTerminationReason.ERROR, firstOf(events, AgentEvent.Terminated.class).reason());
        assertExactlyOneTermination(events);
    }

    @Test
    void duplicateCompletionPublishesSingleFinalReply() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(completed("答复"));
            round.emit(completed("重复答复")); // 重复完成应被忽略
            round.completion.complete(null);
        }));
        Agent agent = new Agent(provider, newRegistry(), Path.of("."));
        Conversation conversation = new Conversation();

        List<AgentEvent> events = runAndDrain(agent, conversation, "重复请求", timeout(2000));

        assertEquals(1, allOf(events, AgentEvent.FinalReply.class).size());
        assertEquals(AgentTerminationReason.COMPLETED, firstOf(events, AgentEvent.Terminated.class).reason());
        assertExactlyOneTermination(events);
    }

    // ---- T14：ReAct 多轮状态机与历史提交 ----

    @Test
    void firstRoundTextOnlyCommitsAndCompletes() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(completed("直接答复"));
            round.completion.complete(null);
        }));
        Agent agent = new Agent(provider, newRegistry(), Path.of("."));
        Conversation conversation = new Conversation();

        List<AgentEvent> events = runAndDrain(agent, conversation, "纯文本", timeout(2000));

        assertEquals(1, provider.requests.size());
        assertTrue(provider.requests.get(0).hasCurrentUserMessage(), "首轮应携带用户消息");
        assertEquals(AgentTerminationReason.COMPLETED, firstOf(events, AgentEvent.Terminated.class).reason());
        assertEquals(2, conversation.size(), "含环境首轮 + 用户轮");
        ConversationTurn turn = conversation.snapshot().get(1);
        assertTrue(turn.hasUserText());
        assertFalse(turn.hasToolCalls());
    }

    @Test
    void twoToolRoundsThenFinalText() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                round -> {
                    round.emit(completedWithTools(List.of(okCall("c1", "ReadFile"))));
                    round.completion.complete(null);
                },
                round -> {
                    round.emit(completedWithTools(List.of(okCall("c2", "WriteFile"))));
                    round.completion.complete(null);
                },
                round -> {
                    round.emit(completed("任务完成"));
                    round.completion.complete(null);
                }
        ));
        ToolRegistry registry = newRegistry();
        registry.register(new StubTool("ReadFile", ToolCategory.READ));
        registry.register(new StubTool("WriteFile", ToolCategory.WRITE));
        Agent agent = new Agent(provider, registry, Path.of("."));
        Conversation conversation = new Conversation();

        List<AgentEvent> events = runAndDrain(agent, conversation, "多轮任务", timeout(4000));

        assertEquals(3, provider.requests.size(), "应发起三次请求");
        assertFalse(provider.requests.get(1).hasCurrentUserMessage(), "后续轮不重复用户消息");
        assertFalse(provider.requests.get(2).hasCurrentUserMessage());
        assertEquals(4, conversation.size(), "含环境首轮 + 每轮一次完整提交");
        assertEquals(List.of("c1"), allToolStarted(events, 1));
        assertEquals(List.of("c2"), allToolStarted(events, 2));
        assertEquals(List.of("c1", "c2"), allOf(events, AgentEvent.ToolFinished.class).stream()
                .map(AgentEvent.ToolFinished::callId).toList());
        ConversationTurn second = conversation.snapshot().get(2);
        assertTrue(second.hasToolCalls());
        assertTrue(second.hasToolResults());
        assertEquals(AgentTerminationReason.COMPLETED, firstOf(events, AgentEvent.Terminated.class).reason());
        assertExactlyOneTermination(events);
    }

    @Test
    void toolParseFailureOccupiesSlotAndContinues() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                round -> {
                    round.emit(completedWithTools(List.of(new AssembledCall(
                            new ToolCall("c1", "ReadFile", Map.of(), "{}", "anthropic"), "参数解析失败: 非法 JSON"))));
                    round.completion.complete(null);
                },
                round -> {
                    round.emit(completed("继续完成"));
                    round.completion.complete(null);
                }
        ));
        ToolRegistry registry = newRegistry();
        registry.register(new StubTool("ReadFile", ToolCategory.READ));
        Agent agent = new Agent(provider, registry, Path.of("."));
        Conversation conversation = new Conversation();

        List<AgentEvent> events = runAndDrain(agent, conversation, "解析失败", timeout(3000));

        assertEquals(2, provider.requests.size());
        ConversationTurn first = conversation.snapshot().get(1);
        List<ConversationContent> resultBlocks = first.contents().stream()
                .filter(c -> c instanceof ConversationContent.ToolResultBlock)
                .toList();
        assertEquals(1, resultBlocks.size());
        ToolResult result = ((ConversationContent.ToolResultBlock) resultBlocks.get(0)).toolResult().result();
        assertEquals(ToolResult.Status.INVALID_ARGUMENT, result.status());
        assertEquals(AgentTerminationReason.COMPLETED, firstOf(events, AgentEvent.Terminated.class).reason());
    }

    @Test
    void unknownToolProducesErrorResultAndContinues() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                round -> {
                    round.emit(completedWithTools(List.of(okCall("c1", "UnknownTool"))));
                    round.completion.complete(null);
                },
                round -> {
                    round.emit(completed("结束"));
                    round.completion.complete(null);
                }
        ));
        Agent agent = new Agent(provider, newRegistry(), Path.of("."));
        Conversation conversation = new Conversation();

        List<AgentEvent> events = runAndDrain(agent, conversation, "未知工具", timeout(3000));

        ConversationTurn first = conversation.snapshot().get(1);
        ConversationContent.ToolResultBlock block = (ConversationContent.ToolResultBlock) first.contents().stream()
                .filter(c -> c instanceof ConversationContent.ToolResultBlock)
                .findFirst().orElseThrow();
        assertFalse(block.toolResult().result().success());
        assertTrue(block.toolResult().result().content().contains("未知工具"));
        assertEquals(AgentTerminationReason.COMPLETED, firstOf(events, AgentEvent.Terminated.class).reason());
    }

    @Test
    void maxTokensPublishesErrorWithoutRetry() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(completedWithReason("", "", ProviderStopReason.MAX_TOKENS));
            round.completion.complete(null);
        }));
        Agent agent = new Agent(provider, newRegistry(), Path.of("."));
        Conversation conversation = new Conversation();

        List<AgentEvent> events = runAndDrain(agent, conversation, "token 上限", timeout(2000));

        assertEquals(1, provider.requests.size(), "MAX_TOKENS 不自动重试");
        AgentEvent.Error error = firstOf(events, AgentEvent.Error.class);
        assertNotNull(error);
        assertTrue(error.safeMessage().contains("token"));
        assertEquals(AgentTerminationReason.ERROR, firstOf(events, AgentEvent.Terminated.class).reason());
        assertExactlyOneTermination(events);
    }

    @Test
    void otherStopReasonPublishesErrorWithoutRetry() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(completedWithReason("", "", ProviderStopReason.OTHER));
            round.completion.complete(null);
        }));
        Agent agent = new Agent(provider, newRegistry(), Path.of("."));
        Conversation conversation = new Conversation();

        List<AgentEvent> events = runAndDrain(agent, conversation, "其他原因", timeout(2000));

        assertEquals(1, provider.requests.size());
        assertNotNull(firstOf(events, AgentEvent.Error.class));
        assertEquals(AgentTerminationReason.ERROR, firstOf(events, AgentEvent.Terminated.class).reason());
    }

    @Test
    void usageAccumulatedPerRoundInHistory() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                round -> {
                    round.emit(completedWithUsage("", "", List.of(okCall("c1", "ReadFile")),
                            usage(10, 5), ProviderStopReason.TOOL_USE));
                    round.completion.complete(null);
                },
                round -> {
                    round.emit(completedWithUsage("", "最终", List.of(),
                            usage(5, 8), ProviderStopReason.END_TURN));
                    round.completion.complete(null);
                }
        ));
        ToolRegistry registry = newRegistry();
        registry.register(new StubTool("ReadFile", ToolCategory.READ));
        Agent agent = new Agent(provider, registry, Path.of("."));
        Conversation conversation = new Conversation();

        runAndDrain(agent, conversation, "用量累计", timeout(3000));

        assertEquals(3, conversation.size(), "含环境首轮 + 两轮");
        ConversationTurn first = conversation.snapshot().get(1);
        assertEquals(10, first.usage().inputTokens());
        assertEquals(5, first.usage().outputTokens());
        ConversationTurn second = conversation.snapshot().get(2);
        assertEquals(5, second.usage().inputTokens());
        assertEquals(8, second.usage().outputTokens());
    }

    // ---- T15：轮数上限与唯一终止 ----

    @Test
    void roundLimitStopsBeforeThirdRound() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                round -> {
                    round.emit(completedWithTools(List.of(okCall("c1", "ReadFile"))));
                    round.completion.complete(null);
                },
                round -> {
                    round.emit(completedWithTools(List.of(okCall("c2", "ReadFile"))));
                    round.completion.complete(null);
                },
                round -> {
                    throw new AssertionError("不应发起第三轮请求");
                }
        ));
        ToolRegistry registry = newRegistry();
        registry.register(new StubTool("ReadFile", ToolCategory.READ));
        Agent agent = new Agent(provider, registry, Path.of("."));
        Conversation conversation = new Conversation();
        AgentRunOptions options = new AgentRunOptions(false, 2, Duration.ofSeconds(2), 64);

        List<AgentEvent> events = runAndDrain(agent, conversation, "轮数上限", options);

        assertEquals(2, provider.requests.size(), "只允许两次请求");
        assertEquals(3, conversation.size(), "含环境首轮 + 第二轮结果已提交");
        AgentEvent.FinalReply reply = firstOf(events, AgentEvent.FinalReply.class);
        assertNotNull(reply, "应发布轮数上限说明");
        assertTrue(reply.text().contains("上限"));
        AgentEvent.Terminated terminated = firstOf(events, AgentEvent.Terminated.class);
        assertEquals(AgentTerminationReason.ROUND_LIMIT, terminated.reason());
        assertEquals(2, terminated.completedRounds());
        assertExactlyOneTermination(events);
    }

    // ---- 测试工具 ----

    private static List<AgentEvent> runAndDrain(Agent agent, Conversation conversation,
                                                String message, AgentRunOptions options) throws Exception {
        AgentRun run = agent.run(conversation, message, options);
        List<AgentEvent> events = new ArrayList<>();
        long deadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
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
        AgentEvent.Terminated terminated = firstOf(events, AgentEvent.Terminated.class);
        assertNotNull(terminated, "应在超时前收到唯一终止事件");
        return events;
    }

    private static AgentRunOptions timeout(int millis) {
        return new AgentRunOptions(false, 20, Duration.ofMillis(millis), 64);
    }

    private static ToolRegistry newRegistry() {
        return new ToolRegistry();
    }

    private static StreamEvent completed(String text) {
        return new StreamEvent.Completed(
                new ProviderResponse("", text, List.of(), ProviderStopReason.END_TURN, Usage.unknown(), null));
    }

    private static StreamEvent completedWithTools(List<AssembledCall> calls) {
        return new StreamEvent.Completed(
                new ProviderResponse("", "", calls, ProviderStopReason.TOOL_USE, Usage.unknown(), null));
    }

    private static StreamEvent completedWithReason(String thinking, String text, ProviderStopReason reason) {
        return new StreamEvent.Completed(
                new ProviderResponse(thinking, text, List.of(), reason, Usage.unknown(), null));
    }

    private static StreamEvent completedWithUsage(String thinking, String text, List<AssembledCall> calls,
                                                  Usage usage, ProviderStopReason reason) {
        return new StreamEvent.Completed(new ProviderResponse(thinking, text, calls, reason, usage, null));
    }

    private static AssembledCall okCall(String id, String name) {
        return new AssembledCall(new ToolCall(id, name, Map.of(), "{}", "anthropic"), null);
    }

    private static Usage usage(int input, int output) {
        return new Usage(input, output, 0, null, null, input + output, null, null, "end_turn", "req-1");
    }

    private static List<String> allToolStarted(List<AgentEvent> events, int round) {
        return events.stream()
                .filter(e -> e instanceof AgentEvent.ToolStarted s && s.round() == round)
                .map(e -> ((AgentEvent.ToolStarted) e).callId())
                .toList();
    }

    private static <T extends AgentEvent> T firstOf(List<AgentEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    private static <T extends AgentEvent> List<T> allOf(List<AgentEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static void assertExactlyOneTermination(List<AgentEvent> events) {
        assertEquals(1, allOf(events, AgentEvent.Terminated.class).size());
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
        volatile AtomicInteger cancelCounter = new AtomicInteger();

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
                    cancelCounter.incrementAndGet();
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
