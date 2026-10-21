package dev.zencode.team;

import dev.zencode.chat.Usage;
import dev.zencode.config.Protocol;
import dev.zencode.config.TeamConfig;
import dev.zencode.prompt.RequestAssembler;
import dev.zencode.prompt.SystemPromptBuilder;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.subagent.SubagentContext;
import dev.zencode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coordinator Mode 测试：双锁任一缺失不激活；双锁齐备后六工具禁用 + Task 可用 +
 * 四阶段提示词注入；deactivate 完全恢复。
 */
class CoordinatorModeTest {

    @TempDir
    Path tmp;

    private TeamManager manager(boolean coordinatorEnabled) {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(completed("完成"));
            round.completion.complete(null);
        }));
        SubagentContext ctx = new SubagentContext(provider, null, tmp, null, null,
                ToolRegistry.createDefault(tmp), null);
        return new TeamManager(tmp, new TeamConfig(coordinatorEnabled), new TeamStore(tmp),
                null, null, ctx, new BackendDetector());
    }

    @Test
    void configLockMissingActivateThrows() {
        TeamManager mgr = manager(false);
        mgr.createTeam("alpha");
        ToolRegistry registry = ToolRegistry.createDefault(tmp);
        RequestAssembler assembler = new RequestAssembler(tmp, "m",
                SystemPromptBuilder.defaults().build(), registry.schemas(Protocol.ANTHROPIC));
        TeamException e = assertThrows(TeamException.class,
                () -> CoordinatorMode.activate(mgr, registry, assembler));
        assertTrue(e.getMessage().contains("coordinator"));
        // 工具未被改动
        assertTrue(registry.isEnabled("Bash"));
    }

    @Test
    void activateNarrowsToolsAndInjectsWorkflow() {
        TeamManager mgr = manager(true);
        mgr.createTeam("alpha");
        ToolRegistry registry = ToolRegistry.createDefault(tmp);
        RequestAssembler assembler = new RequestAssembler(tmp, "m",
                SystemPromptBuilder.defaults().build(), registry.schemas(Protocol.ANTHROPIC));

        CoordinatorMode.activate(mgr, registry, assembler);

        assertTrue(mgr.coordinatorActive());
        // 六工具被禁用：不出现在 schema
        List<String> names = schemaNames(registry.schemas(Protocol.ANTHROPIC));
        for (String tool : CoordinatorMode.NARROWED_TOOLS) {
            assertFalse(names.contains(tool), tool + " 不应出现在 Coordinator 模式 schema");
            assertFalse(registry.isEnabled(tool), tool + " 应被禁用");
        }
        // Task 工具注册
        assertTrue(names.contains("task"), "Task 工具应注册: " + names);
        // 四阶段提示词注入
        assertTrue(assembler.assemble(new dev.zencode.chat.Conversation(), 1, false, "x")
                .systemPrompt().contains("Coordinator Mode 工作流"));
        assertTrue(assembler.assemble(new dev.zencode.chat.Conversation(), 1, false, "x")
                .systemPrompt().contains("规划"));
    }

    @Test
    void deactivateRestoresEverything() {
        TeamManager mgr = manager(true);
        mgr.createTeam("alpha");
        ToolRegistry registry = ToolRegistry.createDefault(tmp);
        RequestAssembler assembler = new RequestAssembler(tmp, "m",
                SystemPromptBuilder.defaults().build(), registry.schemas(Protocol.ANTHROPIC));
        CoordinatorMode.activate(mgr, registry, assembler);
        CoordinatorMode.deactivate(mgr, registry, assembler);

        assertFalse(mgr.coordinatorActive());
        List<String> names = schemaNames(registry.schemas(Protocol.ANTHROPIC));
        for (String tool : CoordinatorMode.NARROWED_TOOLS) {
            assertTrue(registry.isEnabled(tool), tool + " 应恢复启用");
            assertTrue(names.contains(tool), tool + " 应回到 schema");
        }
        assertFalse(names.contains("task"), "Task 工具应注销");
        assertFalse(assembler.assemble(new dev.zencode.chat.Conversation(), 1, false, "x")
                .systemPrompt().contains("Coordinator Mode 工作流"));
    }

    private static List<String> schemaNames(List<Map<String, Object>> schemas) {
        return schemas.stream().map(s -> (String) s.get("name")).toList();
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
        final AtomicInteger requestCount = new AtomicInteger();

        ScriptedProvider(List<Round> rounds) {
            this.rounds = rounds;
        }

        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
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
