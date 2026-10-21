package dev.zencode.agent;

import dev.zencode.chat.Conversation;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T16：Agent 全阶段取消测试。覆盖等待模型、并发 READ 批次、写/命令屏障前取消；
 * 断言活动资源收到取消、已完成轮次保留、未完成响应不提交、无迟到历史写入和唯一取消终止。
 * 注：会话首条为环境 <system-reminder> 已确认历史，计数时需计入该首轮。
 */
class AgentCancellationTest {

    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(8);

    @Test
    void cancelWhileWaitingForModelCancelsHandleAndTerminatesOnce() throws Exception {
        ManualProvider provider = new ManualProvider();
        Agent agent = new Agent(provider, newRegistry(), Path.of("."));
        Conversation conversation = new Conversation();

        AgentRun run = agent.run(conversation, "等待取消", timeout(2000));
        awaitConsumer(provider);
        run.cancel();

        List<AgentEvent> events = drain(run);
        assertEquals(1, provider.cancelCalls.get(), "等待模型时应取消活动流句柄");
        AgentEvent.Terminated terminated = firstOf(events, AgentEvent.Terminated.class);
        assertEquals(AgentTerminationReason.CANCELLED, terminated.reason());
        assertNull(firstOf(events, AgentEvent.FinalReply.class), "取消不发布最终回复");
        assertNull(firstOf(events, AgentEvent.Error.class), "取消不发布错误");
        assertEquals(1, conversation.size(), "仅环境首轮，取消不提交未完成响应");
        assertExactlyOneTermination(events);
    }

    @Test
    void cancelDuringConcurrentReadBatchCancelsResultsAndPreservesHistory() throws Exception {
        ManualProvider provider = new ManualProvider();
        BlockingTool readA = new BlockingTool("ReadA", ToolCategory.READ);
        BlockingTool readB = new BlockingTool("ReadB", ToolCategory.READ);
        ToolRegistry registry = newRegistry();
        registry.register(readA);
        registry.register(readB);
        Agent agent = new Agent(provider, registry, Path.of("."));
        Conversation conversation = new Conversation();

        AgentRun run = agent.run(conversation, "批次取消", timeout(2000));
        awaitConsumer(provider);
        provider.emit(new StreamEvent.Completed(new ProviderResponse("", "", List.of(
                okCall("c1", "ReadA"), okCall("c2", "ReadB")), ProviderStopReason.TOOL_USE, Usage.unknown(), null)));
        provider.completion.complete(null);

        awaitToolStarted(run, 2);
        run.cancel();
        releaseAll(readA, readB);

        List<AgentEvent> events = drain(run);
        AgentEvent.Terminated terminated = firstOf(events, AgentEvent.Terminated.class);
        assertEquals(AgentTerminationReason.CANCELLED, terminated.reason());
        assertNull(firstOf(events, AgentEvent.FinalReply.class));
        assertEquals(1, conversation.size(), "仅环境首轮，取消后当前轮工具结果不应提交");
        assertExactlyOneTermination(events);
    }

    @Test
    void cancelDuringWriteBarrierBeforeExecution() throws Exception {
        ManualProvider provider = new ManualProvider();
        BlockingTool writeC = new BlockingTool("WriteC", ToolCategory.WRITE);
        ToolRegistry registry = newRegistry();
        registry.register(writeC);
        Agent agent = new Agent(provider, registry, Path.of("."));
        Conversation conversation = new Conversation();

        AgentRun run = agent.run(conversation, "写屏障取消", timeout(2000));
        awaitConsumer(provider);
        provider.emit(new StreamEvent.Completed(new ProviderResponse("", "", List.of(
                okCall("c1", "WriteC")), ProviderStopReason.TOOL_USE, Usage.unknown(), null)));
        provider.completion.complete(null);

        awaitToolStarted(run, 1);
        run.cancel();
        releaseAll(writeC);

        List<AgentEvent> events = drain(run);
        assertEquals(AgentTerminationReason.CANCELLED, firstOf(events, AgentEvent.Terminated.class).reason());
        assertEquals(1, conversation.size(), "仅环境首轮，取消后当前轮工具结果不应提交");
        assertExactlyOneTermination(events);
    }

    @Test
    void cancelPreservesAlreadyCommittedRounds() throws Exception {
        ManualProvider provider = new ManualProvider();
        BlockingTool readA = new BlockingTool("ReadA", ToolCategory.READ);
        ToolRegistry registry = newRegistry();
        registry.register(readA);
        Agent agent = new Agent(provider, registry, Path.of("."));
        Conversation conversation = new Conversation();

        AgentRun run = agent.run(conversation, "保留已提交", timeout(2000));

        // 第一轮：工具成功后提交
        awaitConsumer(provider);
        provider.emit(new StreamEvent.Completed(new ProviderResponse("", "", List.of(
                okCall("c1", "ReadA")), ProviderStopReason.TOOL_USE, Usage.unknown(), null)));
        provider.completion.complete(null);
        awaitToolStarted(run, 1);
        releaseAll(readA);
        awaitCommittedRounds(conversation, 2);

        // 第二轮：等待模型时取消
        awaitRequestCount(provider, 2);
        run.cancel();

        List<AgentEvent> events = drain(run);
        AgentEvent.Terminated terminated = firstOf(events, AgentEvent.Terminated.class);
        assertEquals(AgentTerminationReason.CANCELLED, terminated.reason());
        assertEquals(2, conversation.size(), "环境首轮 + 已提交轮保留，未完成轮不提交");
        assertEquals(1, terminated.completedRounds());
        assertExactlyOneTermination(events);
    }

    @Test
    void lateProviderEventsAfterCancelAreIgnored() throws Exception {
        ManualProvider provider = new ManualProvider();
        Agent agent = new Agent(provider, newRegistry(), Path.of("."));
        Conversation conversation = new Conversation();

        AgentRun run = agent.run(conversation, "迟到事件", timeout(2000));
        awaitConsumer(provider);
        run.cancel();

        provider.emit(new StreamEvent.TextDelta("迟到的增量"));
        provider.emit(new StreamEvent.Completed(new ProviderResponse("", "迟到回复", List.of(),
                ProviderStopReason.END_TURN, Usage.unknown(), null)));
        provider.completion.complete(null);

        List<AgentEvent> events = drain(run);
        assertNull(firstOf(events, AgentEvent.FinalReply.class), "取消后的迟到完成不产生最终回复");
        assertNull(firstOf(events, AgentEvent.Error.class));
        assertEquals(1, conversation.size(), "仅环境首轮，取消后的迟到事件不入历史");
        assertEquals(AgentTerminationReason.CANCELLED, firstOf(events, AgentEvent.Terminated.class).reason());
        assertExactlyOneTermination(events);
    }

    @Test
    void repeatedCancelIsIdempotent() throws Exception {
        ManualProvider provider = new ManualProvider();
        Agent agent = new Agent(provider, newRegistry(), Path.of("."));
        Conversation conversation = new Conversation();

        AgentRun run = agent.run(conversation, "重复取消", timeout(2000));
        awaitConsumer(provider);
        run.cancel();
        run.cancel();
        run.cancel();

        List<AgentEvent> events = drain(run);
        assertExactlyOneTermination(events);
        assertEquals(AgentTerminationReason.CANCELLED, firstOf(events, AgentEvent.Terminated.class).reason());
    }

    // ---- 测试工具 ----

    private static AgentRunOptions timeout(int millis) {
        return new AgentRunOptions(false, 20, Duration.ofMillis(millis), 64);
    }

    private static ToolRegistry newRegistry() {
        return new ToolRegistry();
    }

    private static AssembledCall okCall(String id, String name) {
        return new AssembledCall(new ToolCall(id, name, Map.of(), "{}", "anthropic"), null);
    }

    private static void awaitConsumer(ManualProvider provider) throws InterruptedException {
        if (!provider.consumerLatch.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Provider 未在超时前收到请求");
        }
    }

    private static void awaitRequestCount(ManualProvider provider, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (provider.requests.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, provider.requests.size(), "请求轮数应达到预期");
    }

    private static void awaitToolStarted(AgentRun run, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        int count = 0;
        while (count < expected && System.nanoTime() < deadline) {
            AgentEvent event = run.pollEvent(Duration.ofMillis(100));
            if (event instanceof AgentEvent.ToolStarted) count++;
        }
        assertEquals(expected, count, "应发布工具开始事件");
    }

    private static void awaitCommittedRounds(Conversation conversation, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (conversation.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, conversation.size());
    }

    private static void releaseAll(BlockingTool... tools) {
        for (BlockingTool tool : tools) {
            tool.release.countDown();
        }
    }

    private static List<AgentEvent> drain(AgentRun run) throws Exception {
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
        assertNotNull(firstOf(events, AgentEvent.Terminated.class), "应在超时前收到唯一终止事件");
        return events;
    }

    private static <T extends AgentEvent> T firstOf(List<AgentEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    private static void assertExactlyOneTermination(List<AgentEvent> events) {
        assertEquals(1, events.stream().filter(e -> e instanceof AgentEvent.Terminated).count());
    }

    private static final class BlockingTool extends BaseTool {
        final CountDownLatch release = new CountDownLatch(1);

        BlockingTool(String name, ToolCategory category) {
            super(name, "阻塞测试工具 " + name, category, ToolPermission.READ_ONLY, Map.of());
        }

        @Override
        protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    return ToolResult.timeout(ctx.callId(), name(), "释放超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.cancelled(ctx.callId(), name(), "中断");
            }
            if (ctx.isCancelled()) {
                return ToolResult.cancelled(ctx.callId(), name(), "已取消");
            }
            return ToolResult.success(ctx.callId(), name(), "ok");
        }
    }

    private static final class ManualProvider implements ChatProvider {
        final CountDownLatch consumerLatch = new CountDownLatch(1);
        final AtomicInteger cancelCalls = new AtomicInteger();
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        volatile Consumer<StreamEvent> consumer;
        final List<ProviderRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> c) {
            requests.add(request);
            consumer = c;
            consumerLatch.countDown();
            return new StreamHandle() {
                @Override
                public void cancel() {
                    cancelCalls.incrementAndGet();
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

        void emit(StreamEvent event) {
            Consumer<StreamEvent> c = consumer;
            if (c != null) c.accept(event);
        }
    }}