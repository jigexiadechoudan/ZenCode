package dev.zencode.app;

import dev.zencode.agent.Agent;
import dev.zencode.agent.AgentEvent;
import dev.zencode.agent.AgentRun;
import dev.zencode.agent.AgentRunOptions;
import dev.zencode.agent.AgentTerminationReason;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Usage;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.hitl.HitlRequest;
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
import dev.zencode.tui.ConversationView;
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
 * T18：Controller 多轮多工具 Agent 事件适配测试。使用真实 Agent + 脚本化 FakeProvider，
 * 验证全部工具按序执行、安全摘要展示、历史一次提交和终止清理。
 */
class ConversationToolFlowTest {
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(8);

    @Test
    void multiRoundMultiToolFlowExecutesAllToolsAndCommits() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                round -> {
                    round.emit(completedWithTools(List.of(
                            okCall("c1", "ReadFile"),
                            okCall("c2", "WriteFile"))));
                    round.completion.complete(null);
                },
                round -> {
                    round.emit(completedWithTools(List.of(okCall("c3", "Grep"))));
                    round.completion.complete(null);
                },
                round -> {
                    round.emit(completed("全部完成"));
                    round.completion.complete(null);
                }
        ));
        Path workDir = Path.of(System.getProperty("user.dir"));
        ToolRegistry registry = ToolRegistry.createDefault(workDir);
        AtomicInteger executions = new AtomicInteger();
        registry.register(new CountingTool("ReadFile", ToolCategory.READ, executions));
        registry.register(new CountingTool("WriteFile", ToolCategory.WRITE, executions));
        registry.register(new CountingTool("Grep", ToolCategory.READ, executions));
        Agent agent = new Agent(provider, registry, workDir);
        Conversation conversation = new Conversation();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view,
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64));

        assertTrue(controller.submit("多工具任务"));
        awaitIdle(controller);

        assertEquals(3, executions.get(), "所有工具都应执行");
        assertEquals(3, provider.requests.size(), "三轮请求");
        assertEquals(4, conversation.size(), "含环境首轮 + 每轮一次完整提交");

        // 工具开始与完成事件按模型顺序展示，只含安全摘要
        assertEquals(3, view.started.stream().filter(s -> s.startsWith("tool_start")).count());
        assertEquals(3, view.finished.stream().filter(s -> s.startsWith("tool_completed")).count());
        assertFalse(view.events.stream().anyMatch(s -> s.contains("OUTPUT_TAIL_987")), "结果只显示安全摘要");

        // 第二轮提交的历史包含工具调用与结果，且不重复用户消息
        ConversationTurn second = conversation.snapshot().get(2);
        assertTrue(second.hasToolCalls());
        assertTrue(second.hasToolResults());
        assertFalse(second.hasUserText(), "后续轮不重复用户消息");

        assertEquals("全部完成", view.answer);
        assertEquals(1, view.finishedMessages);
        assertFalse(controller.isBusy());
    }

    @Test
    void restrictedToolStillProducesPairedResultAndFinalPlan() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                round -> {
                    round.emit(completedWithTools(List.of(
                            okCall("c1", "ReadFile"),
                            okCall("c2", "Bash"))));
                    round.completion.complete(null);
                },
                round -> {
                    round.emit(completed("实施计划"));
                    round.completion.complete(null);
                }
        ));
        Path workDir = Path.of(System.getProperty("user.dir"));
        ToolRegistry registry = ToolRegistry.createDefault(workDir);
        AtomicInteger executions = new AtomicInteger();
        registry.register(new CountingTool("ReadFile", ToolCategory.READ, executions));
        registry.register(new CountingTool("Bash", ToolCategory.COMMAND, executions));
        Agent agent = new Agent(provider, registry, workDir);
        Conversation conversation = new Conversation();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view,
                new AgentRunOptions(true, 20, Duration.ofSeconds(2), 64));

        assertTrue(controller.submit("调研并出计划"));
        awaitIdle(controller);

        assertEquals(1, executions.get(), "plan-only 只执行只读工具");
        ConversationTurn first = conversation.snapshot().get(1);
        List<ConversationContent.ToolResultBlock> results = first.contents().stream()
                .filter(c -> c instanceof ConversationContent.ToolResultBlock)
                .map(c -> (ConversationContent.ToolResultBlock) c)
                .toList();
        assertEquals(2, results.size(), "受限结果也按原顺序配对");
        assertEquals(ToolResult.Status.RESTRICTED, results.get(1).toolResult().result().status());
        assertEquals("c2", results.get(1).callId());
        assertEquals("实施计划", view.answer);
        assertFalse(controller.isBusy());
    }

    private static void awaitIdle(ConversationController controller) throws Exception {
        long deadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
        while (controller.isBusy() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(controller.isBusy(), "应在超时前释放 busy");
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

    private static final class CountingTool extends BaseTool {
        final ToolCategory category;
        final AtomicInteger executions;

        CountingTool(String name, ToolCategory category, AtomicInteger executions) {
            super(name, "测试工具 " + name, category, ToolPermission.READ_ONLY, Map.of());
            this.category = category;
            this.executions = executions;
        }

        @Override
        protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
            executions.incrementAndGet();
            return ToolResult.success(ctx.callId(), name(),
                    name() + " 执行结果\n" + "B".repeat(400) + "OUTPUT_TAIL_987");
        }
    }

    private static final class RecordingView implements ConversationView {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> started = new CopyOnWriteArrayList<>();
        final List<String> finished = new CopyOnWriteArrayList<>();
        volatile String answer = "";
        volatile int finishedMessages;
        volatile boolean busy;

        @Override public void showUserMessage(String s) { events.add("user:" + s); }
        @Override public void beginAssistantMessage() { events.add("begin"); }
        @Override public void appendThinking(String s) { events.add("thinking:" + s); }
        @Override public void appendAnswer(String s) { answer += s; events.add("answer:" + s); }
        @Override public void finishAssistantMessage() { finishedMessages++; events.add("finish"); }
        @Override public void markAssistantAborted() { events.add("aborted"); }
        @Override public void showError(String s) { events.add("error:" + s); }
        @Override public void showUsage(Usage c, Usage t) { events.add("usage"); }
        @Override public void setBusy(boolean b) { busy = b; }
        @Override public void showToolEvent(String type, String toolName, String callId, String summary) {
            String entry = type + ":" + toolName + ":" + summary;
            events.add(entry);
            if (type.startsWith("tool_start")) started.add(entry);
            if (type.startsWith("tool_completed")) finished.add(entry);
        }
        @Override public void showPermissionRequest(HitlRequest request) {}
        @Override public boolean resolvePermission(HitlDecision decision) { return false; }
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
