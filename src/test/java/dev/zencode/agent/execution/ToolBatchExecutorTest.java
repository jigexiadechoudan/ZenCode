package dev.zencode.agent.execution;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T12：工具分段执行测试。覆盖只读并发、写/命令串行屏障、反序完成同序输出、
 * 单项失败不取消同批、每个调用恰好一个结果，以及 T11 拦截器在批处理器中的行为。
 */
class ToolBatchExecutorTest {

    private static ToolCall call(String id, String name) {
        return new ToolCall(id, name, Map.of(), "{}", "anthropic");
    }

    private static AssembledCall ok(String id, String name) {
        return new AssembledCall(call(id, name), null);
    }

    private static AssembledCall parseFailed(String id, String name) {
        return new AssembledCall(call(id, name), "参数解析失败: 非法 JSON");
    }

    private static ToolRegistry registryWith(ToolBatchExecutorTest.InstrumentedTool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (ToolBatchExecutorTest.InstrumentedTool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    @Test
    void readBatchRunsConcurrently() throws Exception {
        AtomicInteger sharedActive = new AtomicInteger();
        AtomicInteger sharedMax = new AtomicInteger();
        InstrumentedTool readA = new InstrumentedTool("ReadA", ToolCategory.READ, sharedActive, sharedMax);
        InstrumentedTool readB = new InstrumentedTool("ReadB", ToolCategory.READ, sharedActive, sharedMax);
        CountDownLatch go = new CountDownLatch(1);
        readA.release = go;
        readB.release = go;
        ToolBatchExecutor executor = new ToolBatchExecutor(registryWith(readA, readB));

        ExecutorService runner = Executors.newSingleThreadExecutor();
        Future<List<ToolResult>> future = runner.submit(() ->
                executor.execute(List.of(ok("A", "ReadA"), ok("B", "ReadB")),
                        ToolBatchContext.normal(Path.of("."), 0)));
        try {
            assertTrue(readA.entered.await(2, TimeUnit.SECONDS), "ReadA 应已进入");
            assertTrue(readB.entered.await(2, TimeUnit.SECONDS), "ReadB 应已进入");
            assertEquals(2, readA.maxActive.get(), "同批只读应并发执行");
            go.countDown();
            List<ToolResult> results = future.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("A", "B"), results.stream().map(ToolResult::callId).toList());
            assertTrue(results.stream().allMatch(ToolResult::success));
        } finally {
            go.countDown();
            runner.shutdownNow();
        }
    }

    @Test
    void writeAndCommandAreSerialBarriers() throws Exception {
        InstrumentedTool readA = new InstrumentedTool("ReadA", ToolCategory.READ);
        InstrumentedTool writeC = new InstrumentedTool("WriteC", ToolCategory.WRITE);
        InstrumentedTool readD = new InstrumentedTool("ReadD", ToolCategory.READ);
        InstrumentedTool commandE = new InstrumentedTool("CommandE", ToolCategory.COMMAND);
        InstrumentedTool writeF = new InstrumentedTool("WriteF", ToolCategory.WRITE);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch releaseC = new CountDownLatch(1);
        CountDownLatch releaseD = new CountDownLatch(1);
        CountDownLatch releaseE = new CountDownLatch(1);
        CountDownLatch releaseF = new CountDownLatch(1);
        readA.release = releaseA;
        writeC.release = releaseC;
        readD.release = releaseD;
        commandE.release = releaseE;
        writeF.release = releaseF;

        ToolBatchExecutor executor = new ToolBatchExecutor(registryWith(readA, writeC, readD, commandE, writeF));
        ExecutorService runner = Executors.newSingleThreadExecutor();
        Future<List<ToolResult>> future = runner.submit(() -> executor.execute(
                List.of(ok("A", "ReadA"), ok("C", "WriteC"), ok("D", "ReadD"),
                        ok("E", "CommandE"), ok("F", "WriteF")),
                ToolBatchContext.normal(Path.of("."), 0)));
        try {
            assertTrue(readA.entered.await(2, TimeUnit.SECONDS), "ReadA 应最先执行");
            assertEquals(1, writeC.entered.getCount(), "写工具未启动前不应执行");
            releaseA.countDown();

            assertTrue(writeC.entered.await(2, TimeUnit.SECONDS), "WriteC 应串行执行");
            assertEquals(1, readD.entered.getCount(), "写屏障未结束前不应执行后续只读");
            releaseC.countDown();

            assertTrue(readD.entered.await(2, TimeUnit.SECONDS), "ReadD 应在写屏障后执行");
            assertEquals(1, commandE.entered.getCount(), "命令工具未启动前不应执行");
            releaseD.countDown();

            assertTrue(commandE.entered.await(2, TimeUnit.SECONDS), "CommandE 应串行执行");
            assertEquals(1, writeF.entered.getCount(), "命令屏障未结束前不应执行后续写工具");
            releaseE.countDown();

            assertTrue(writeF.entered.await(2, TimeUnit.SECONDS), "WriteF 应最后执行");
            releaseF.countDown();

            List<ToolResult> results = future.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("A", "C", "D", "E", "F"),
                    results.stream().map(ToolResult::callId).toList());
            assertTrue(results.stream().allMatch(ToolResult::success));
        } finally {
            releaseA.countDown();
            releaseC.countDown();
            releaseD.countDown();
            releaseE.countDown();
            releaseF.countDown();
            runner.shutdownNow();
        }
    }

    @Test
    void reverseCompletionPreservesInputOrder() throws Exception {
        InstrumentedTool readA = new InstrumentedTool("ReadA", ToolCategory.READ);
        InstrumentedTool readB = new InstrumentedTool("ReadB", ToolCategory.READ);
        readA.delayMillis = 200;
        readB.delayMillis = 0;
        ToolBatchExecutor executor = new ToolBatchExecutor(registryWith(readA, readB));

        List<ToolResult> results = executor.execute(
                List.of(ok("A", "ReadA"), ok("B", "ReadB")),
                ToolBatchContext.normal(Path.of("."), 0));

        assertEquals(List.of("A", "B"), results.stream().map(ToolResult::callId).toList());
        assertTrue(results.get(0).success());
        assertTrue(results.get(1).success());
    }

    @Test
    void singleFailureDoesNotCancelSameBatch() {
        InstrumentedTool readA = new InstrumentedTool("ReadA", ToolCategory.READ);
        InstrumentedTool readB = new InstrumentedTool("ReadB", ToolCategory.READ);
        readA.fail = true;
        ToolBatchExecutor executor = new ToolBatchExecutor(registryWith(readA, readB));

        List<ToolResult> results = executor.execute(
                List.of(ok("A", "ReadA"), ok("B", "ReadB")),
                ToolBatchContext.normal(Path.of("."), 0));

        assertEquals(2, results.size());
        assertFalse(results.get(0).success());
        assertEquals("A", results.get(0).callId());
        assertTrue(results.get(1).success());
        assertEquals("B", results.get(1).callId());
    }

    @Test
    void parseFailureOccupiesItsSlot() {
        InstrumentedTool readA = new InstrumentedTool("ReadA", ToolCategory.READ);
        ToolBatchExecutor executor = new ToolBatchExecutor(registryWith(readA));

        List<ToolResult> results = executor.execute(
                List.of(ok("A", "ReadA"), parseFailed("B", "ReadB")),
                ToolBatchContext.normal(Path.of("."), 0));

        assertEquals(List.of("A", "B"), results.stream().map(ToolResult::callId).toList());
        assertEquals(ToolResult.Status.INVALID_ARGUMENT, results.get(1).status());
        assertTrue(results.get(0).success());
    }

    @Test
    void unknownToolOccupiesItsSlot() {
        InstrumentedTool readA = new InstrumentedTool("ReadA", ToolCategory.READ);
        ToolBatchExecutor executor = new ToolBatchExecutor(registryWith(readA));

        List<ToolResult> results = executor.execute(
                List.of(ok("A", "ReadA"), ok("U", "UnknownTool")),
                ToolBatchContext.normal(Path.of("."), 0));

        assertEquals(List.of("A", "U"), results.stream().map(ToolResult::callId).toList());
        assertEquals(ToolResult.Status.ERROR, results.get(1).status());
        assertTrue(results.get(1).content().contains("未知工具"));
    }

    @Test
    void disabledToolOccupiesItsSlot() {
        InstrumentedTool readA = new InstrumentedTool("ReadA", ToolCategory.READ);
        InstrumentedTool writeB = new InstrumentedTool("WriteB", ToolCategory.WRITE);
        ToolRegistry registry = registryWith(readA, writeB);
        registry.disable("WriteB");
        ToolBatchExecutor executor = new ToolBatchExecutor(registry);

        List<ToolResult> results = executor.execute(
                List.of(ok("A", "ReadA"), ok("B", "WriteB")),
                ToolBatchContext.normal(Path.of("."), 0));

        assertEquals(ToolResult.Status.DISABLED, results.get(1).status());
        assertEquals("B", results.get(1).callId());
    }

    @Test
    void eachCallPublishesExactlyOneStartedAndOneFinished() {
        InstrumentedTool readA = new InstrumentedTool("ReadA", ToolCategory.READ);
        InstrumentedTool writeB = new InstrumentedTool("WriteB", ToolCategory.WRITE);
        RecordingSink sink = new RecordingSink();
        ToolBatchExecutor executor = new ToolBatchExecutor(registryWith(readA, writeB));
        ToolBatchContext context = new ToolBatchContext(Path.of("."), new AtomicBoolean(false),
                false, ToolInterceptorChain.empty(), sink, 3);

        List<ToolResult> results = executor.execute(
                List.of(ok("A", "ReadA"), ok("B", "WriteB")), context);

        assertEquals(List.of("A", "B"), results.stream().map(ToolResult::callId).toList());
        assertEquals(List.of("A", "B"), sink.started);
        assertEquals(List.of("A", "B"), sink.finished);
        assertEquals(3, sink.startedRound);
        assertEquals(3, sink.finishedRound);
    }

    // ---- T11 拦截器在批处理器中的行为 ----

    @Test
    void defaultInterceptorAllowsAllTools() {
        InstrumentedTool readA = new InstrumentedTool("ReadA", ToolCategory.READ);
        InstrumentedTool writeB = new InstrumentedTool("WriteB", ToolCategory.WRITE);
        ToolBatchExecutor executor = new ToolBatchExecutor(registryWith(readA, writeB));

        List<ToolResult> results = executor.execute(
                List.of(ok("A", "ReadA"), ok("B", "WriteB")),
                ToolBatchContext.normal(Path.of("."), 0));

        assertTrue(results.stream().allMatch(ToolResult::success));
    }

    @Test
    void planOnlyRejectsWriteButAllowsRead() {
        InstrumentedTool readA = new InstrumentedTool("ReadA", ToolCategory.READ);
        InstrumentedTool writeB = new InstrumentedTool("WriteB", ToolCategory.WRITE);
        ToolRegistry registry = registryWith(readA, writeB);
        ToolExecutionInterceptor planOnly = new PlanOnlyInterceptor(registry);
        ToolBatchExecutor executor = new ToolBatchExecutor(registry);
        ToolBatchContext context = new ToolBatchContext(Path.of("."), new AtomicBoolean(false),
                true, planOnly, ToolEventSink.NOOP, 0);

        List<ToolResult> results = executor.execute(
                List.of(ok("A", "ReadA"), ok("B", "WriteB")), context);

        assertTrue(results.get(0).success());
        assertEquals(ToolResult.Status.RESTRICTED, results.get(1).status());
        assertEquals("B", results.get(1).callId());
        assertEquals("WriteB", results.get(1).toolName());
        assertEquals(1, writeB.entered.getCount(), "受限工具不应执行");
    }

    @Test
    void afterInterceptorObservesResult() {
        AtomicInteger observed = new AtomicInteger(0);
        InstrumentedTool readA = new InstrumentedTool("ReadA", ToolCategory.READ);
        ToolExecutionInterceptor observer = new ToolExecutionInterceptor() {
            @Override
            public ToolExecutionInterceptor.BeforeDecision before(ToolCall call, ToolExecutionContext context) {
                return new ToolExecutionInterceptor.BeforeDecision.Allow();
            }

            @Override
            public ToolResult after(ToolCall call, ToolResult result, ToolExecutionContext context) {
                observed.set(result.success() ? 1 : 0);
                return result;
            }
        };
        ToolBatchExecutor executor = new ToolBatchExecutor(registryWith(readA));
        ToolBatchContext context = new ToolBatchContext(Path.of("."), new AtomicBoolean(false),
                false, new ToolInterceptorChain(List.of(observer)), ToolEventSink.NOOP, 0);

        List<ToolResult> results = executor.execute(List.of(ok("A", "ReadA")), context);

        assertTrue(results.get(0).success());
        assertEquals(1, observed.get());
    }

    @Test
    void interceptorExceptionBecomesSafeFailure() {
        ToolExecutionInterceptor throwing = new ToolExecutionInterceptor() {
            @Override
            public ToolExecutionInterceptor.BeforeDecision before(ToolCall call, ToolExecutionContext context) {
                throw new IllegalStateException("堆栈不应泄露");
            }

            @Override
            public ToolResult after(ToolCall call, ToolResult result, ToolExecutionContext context) {
                return result;
            }
        };
        InstrumentedTool readA = new InstrumentedTool("ReadA", ToolCategory.READ);
        ToolBatchExecutor executor = new ToolBatchExecutor(registryWith(readA));
        ToolBatchContext context = new ToolBatchContext(Path.of("."), new AtomicBoolean(false),
                false, new ToolInterceptorChain(List.of(throwing)), ToolEventSink.NOOP, 0);

        List<ToolResult> results = executor.execute(List.of(ok("A", "ReadA")), context);

        assertFalse(results.get(0).success());
        assertFalse(results.get(0).content().contains("堆栈不应泄露"));
        assertEquals(1, readA.entered.getCount(), "异常应阻止工具执行");
    }

    // ---- 测试工具与记录器 ----

    private static final class InstrumentedTool extends BaseTool {
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release;
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        volatile long delayMillis;
        volatile boolean fail;

        InstrumentedTool(String name, ToolCategory category) {
            this(name, category, new AtomicInteger(), new AtomicInteger());
        }

        InstrumentedTool(String name, ToolCategory category, AtomicInteger sharedActive, AtomicInteger sharedMax) {
            super(name, "测试工具 " + name, category, ToolPermission.READ_ONLY, Map.of());
            this.active = sharedActive;
            this.maxActive = sharedMax;
        }

        @Override
        protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
            int current = active.incrementAndGet();
            maxActive.updateAndGet(m -> Math.max(m, current));
            try {
                entered.countDown();
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                if (release != null && !release.await(5, TimeUnit.SECONDS)) {
                    return ToolResult.error(ctx.callId(), name(), "释放超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.cancelled(ctx.callId(), name(), "中断");
            } finally {
                active.decrementAndGet();
            }
            if (fail) {
                return ToolResult.error(ctx.callId(), name(), "模拟失败");
            }
            return ToolResult.success(ctx.callId(), name(), "ok-" + name());
        }
    }

    private static final class RecordingSink implements ToolEventSink {
        final List<String> started = new ArrayList<>();
        final List<String> finished = new ArrayList<>();
        volatile int startedRound = -1;
        volatile int finishedRound = -1;

        @Override
        public void started(String callId, String toolName, String safeSummary, int round) {
            started.add(callId);
            startedRound = round;
        }

        @Override
        public void finished(String callId, String toolName, ToolResult result, int round) {
            finished.add(callId);
            finishedRound = round;
        }
    }
}
