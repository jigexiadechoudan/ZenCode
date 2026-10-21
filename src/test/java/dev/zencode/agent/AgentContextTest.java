package dev.zencode.agent;

import dev.zencode.agent.execution.ToolInterceptorChain;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.config.ContextConfig;
import dev.zencode.context.ContextCompressor;
import dev.zencode.context.SummaryGenerator;
import dev.zencode.context.ToolResultSpooler;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** 集成：Agent 每轮请求前自动压缩，摘要请求发生且历史被替换；未注入压缩器行为不变。 */
class AgentContextTest {

    @TempDir
    Path tempDir;

    @Test
    void compressesHistoryBeforeRequests() throws Exception {
        ScriptedProvider provider = new ScriptedProvider();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("read", ToolCategory.READ));
        ContextConfig config = new ContextConfig(50, 0.8, 4096, 32768, 3);
        ContextCompressor compressor = new ContextCompressor(
                new ToolResultSpooler(tempDir, config),
                new SummaryGenerator(provider, Duration.ofSeconds(2)),
                config);
        Agent agent = new Agent(provider, registry, tempDir,
                ToolInterceptorChain.empty(), "m", compressor);

        Conversation conversation = new Conversation();
        AgentRun run = agent.run(conversation, "开始任务", timeout(4));
        List<AgentEvent> events = drain(run);

        AgentTerminationReason reason = run.completion().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(AgentTerminationReason.COMPLETED, reason);
        assertTrue(provider.summaryCalls() >= 1, "请求前应至少触发一次自动压缩");
        assertEquals(1, events.stream().filter(e -> e instanceof AgentEvent.Terminated).count());

        List<ConversationTurn> turns = conversation.snapshot();
        assertFalse(turns.isEmpty());
        assertTrue(turns.stream().anyMatch(t -> containsBoundary(t)),
                "历史应含边界消息轮");
        assertTrue(turns.get(0).contents().size() == 1
                        && turns.get(0).contents().get(0).toString().contains("环境信息"),
                "环境快照轮应保留在首位: " + turns.get(0));
    }

    @Test
    void withoutCompressorBehaviorUnchanged() throws Exception {
        ScriptedProvider provider = new ScriptedProvider();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("read", ToolCategory.READ));
        Agent agent = new Agent(provider, registry, tempDir,
                ToolInterceptorChain.empty(), "m");

        AgentRun run = agent.run(new Conversation(), "普通任务", timeout(3));
        AgentTerminationReason reason = run.completion().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(AgentTerminationReason.COMPLETED, reason);
        assertEquals(0, provider.summaryCalls(), "未注入压缩器不应发生摘要请求");
    }

    private static boolean containsBoundary(ConversationTurn turn) {
        return turn.contents().stream()
                .anyMatch(c -> c.toString().contains(ContextCompressor.BOUNDARY_MESSAGE));
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

    private static final class StubTool extends BaseTool {
        StubTool(String name, ToolCategory category) {
            super(name, "测试工具 " + name, category, ToolPermission.READ_ONLY, Map.of());
        }

        @Override
        protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
            // 大结果让历史估算快速逼近小窗口阈值
            return ToolResult.success(ctx.callId(), name(), "文件内容" + "a".repeat(500));
        }
    }

    private static final class ScriptedProvider implements ChatProvider {
        final AtomicInteger mainCalls = new AtomicInteger();
        final AtomicInteger summaryCalls = new AtomicInteger();

        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            if (request.toolSchemas().isEmpty()) {
                // 摘要请求：无工具 Schema、无当前用户消息
                summaryCalls.incrementAndGet();
                consumer.accept(new StreamEvent.TextDelta("<summary>压缩摘要</summary>"));
                consumer.accept(completed("", "", List.of()));
            } else {
                int round = mainCalls.incrementAndGet();
                if (round <= 2) {
                    consumer.accept(completed("", "思考",
                            List.of(new AssembledCall(
                                    new ToolCall("c" + round, "read", Map.of(), "{}", "anthropic"), null))));
                } else {
                    consumer.accept(completed("", "完成", List.of()));
                }
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

        int summaryCalls() {
            return summaryCalls.get();
        }

        private static StreamEvent completed(String thinking, String text, List<AssembledCall> calls) {
            return new StreamEvent.Completed(new ProviderResponse(
                    thinking, text, calls,
                    calls.isEmpty() ? ProviderStopReason.END_TURN : ProviderStopReason.TOOL_USE,
                    null, null));
        }
    }
}
