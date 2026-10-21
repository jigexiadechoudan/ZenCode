package dev.zencode.subagent;

import dev.zencode.chat.ChatMessage;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Role;
import dev.zencode.chat.Usage;
import dev.zencode.permission.PermissionMode;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SubagentRunnerTest {

    private static ToolRegistry sourceRegistry() {
        return ToolRegistry.createDefault(Path.of("."));
    }

    private static AgentDefinition exploreDef() {
        return new AgentDefinition("explore", "只读探索", Optional.empty(), 8,
                PermissionMode.READ_ONLY, List.of("ReadFile", "Grep", "Glob"), List.of(),
                "你是只读探索助手，禁止修改文件", false,
                new AgentSource(AgentSource.Level.BUILTIN, Path.of("explore.md")));
    }

    @Test
    void 定义式注入角色指令并过滤工具集返回结果() {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(new StreamEvent.TextDelta("结果"));
            round.emit(completed("探索结论是 X"));
            round.completion.complete(null);
        }));
        TraceRegistry trace = new TraceRegistry();
        SubagentContext ctx = new SubagentContext(provider, null, Path.of("."),
                null, trace, sourceRegistry(), null);

        SubagentResult result = new SubagentRunner().runDefinition(exploreDef(), "找一下 main 方法", ctx);

        assertEquals(TaskStatus.COMPLETED, result.status());
        assertEquals("探索结论是 X", result.output());

        ProviderRequest request = provider.requests.get(0);
        assertTrue(request.systemPrompt().contains("只读探索助手"), "systemPrompt 应含角色指令");
        List<String> names = schemaNames(request.toolSchemas());
        assertTrue(names.contains("ReadFile") && names.contains("Grep") && names.contains("Glob"), names.toString());
        assertFalse(names.contains("Bash"), "角色白名单外工具不应暴露: " + names);
        assertFalse(names.contains("agent"), "委派工具不应暴露: " + names);
    }

    @Test
    void fork继承父历史并注入强硬约束() {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(completed("Fork 结果"));
            round.completion.complete(null);
        }));
        TraceRegistry trace = new TraceRegistry();
        SubagentContext ctx = new SubagentContext(provider, null, Path.of("."),
                null, trace, sourceRegistry(), null);

        Conversation parent = new Conversation();
        parent.commit(new ConversationTurn(new ChatMessage(Role.USER, "父任务"),
                new ChatMessage(Role.ASSISTANT, "父答复"), null, Usage.unknown()));

        SubagentResult result = new SubagentRunner().runFork(parent, "继续分析", ctx);

        assertEquals(TaskStatus.COMPLETED, result.status());
        ProviderRequest request = provider.requests.get(0);
        assertTrue(request.history().size() >= 1, "应继承父历史");
        assertNotNull(request.currentUserMessage());
        assertTrue(request.currentUserMessage().text().contains("禁止再次 fork"));
        assertTrue(request.currentUserMessage().text().contains("继续分析"));
    }

    @Test
    void 模型失败返回FAILED状态() {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(new StreamEvent.Failed("模型失败"));
        }));
        TraceRegistry trace = new TraceRegistry();
        SubagentContext ctx = new SubagentContext(provider, null, Path.of("."),
                null, trace, sourceRegistry(), null);

        SubagentResult result = new SubagentRunner().runDefinition(exploreDef(), "任务", ctx);

        assertEquals(TaskStatus.FAILED, result.status());
    }

    private static List<String> schemaNames(List<Map<String, Object>> schemas) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> s : schemas) {
            Object name = s.get("name");
            if (name instanceof String str) names.add(str);
        }
        return names;
    }

    private static StreamEvent completed(String text) {
        return new StreamEvent.Completed(
                new ProviderResponse("", text, List.of(), ProviderStopReason.END_TURN, Usage.unknown(), null));
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
