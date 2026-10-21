package dev.zencode.subagent;

import dev.zencode.chat.Usage;
import dev.zencode.permission.PermissionMode;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.worktree.GitCommand;
import dev.zencode.worktree.WorktreeConfig;
import dev.zencode.worktree.WorktreeManager;
import dev.zencode.worktree.WorktreeSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SubagentRunnerIsolationTest {

    @TempDir
    Path tmp;

    private static Path initRepo(Path dir) throws IOException {
        Files.createDirectories(dir);
        GitCommand.run(dir, "init");
        Files.writeString(dir.resolve("a.txt"), "hello\n", StandardCharsets.UTF_8);
        GitCommand.run(dir, "add", "a.txt");
        GitCommand.run(dir, "-c", "user.email=test@test.com",
                "-c", "user.name=test", "commit", "-m", "init");
        return dir;
    }

    private static AgentDefinition isolatedDef() {
        return new AgentDefinition("explore", "探索", Optional.empty(), 8,
                PermissionMode.READ_ONLY, List.of("ReadFile", "Grep", "Glob"), List.of(),
                "只读探索", true,
                new AgentSource(AgentSource.Level.BUILTIN, Path.of("explore.md")));
    }

    @Test
    void 隔离模式创建worktree注入通知后自动清理() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        ScriptedProvider provider = new ScriptedProvider("探索结果");
        WorktreeManager wm = new WorktreeManager(repo, WorktreeConfig.defaults(),
                new WorktreeSessionStore(repo), () -> {});
        SubagentContext ctx = new SubagentContext(provider, null, repo, null,
                new TraceRegistry(), ToolRegistry.createDefault(repo), wm);

        SubagentResult result = new SubagentRunner().runDefinition(isolatedDef(), "找 main", ctx);

        assertEquals(TaskStatus.COMPLETED, result.status());
        assertEquals("探索结果", result.output());
        assertTrue(provider.requests.get(0).currentUserMessage().text().contains("隔离工作目录"),
                "任务文本应注入路径翻译上下文");
        assertTrue(wm.list().isEmpty(), "无变更时完成后应自动清理 worktree");
    }

    @Test
    void 非隔离模式复用共享目录() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        ScriptedProvider provider = new ScriptedProvider("共享结果");
        WorktreeManager wm = new WorktreeManager(repo, WorktreeConfig.defaults(),
                new WorktreeSessionStore(repo), () -> {});
        SubagentContext ctx = new SubagentContext(provider, null, repo, null,
                new TraceRegistry(), ToolRegistry.createDefault(repo), wm);

        AgentDefinition def = new AgentDefinition("explore", "探索", Optional.empty(), 8,
                PermissionMode.READ_ONLY, List.of("ReadFile", "Grep", "Glob"), List.of(),
                "只读探索", false,
                new AgentSource(AgentSource.Level.BUILTIN, Path.of("explore.md")));

        SubagentResult result = new SubagentRunner().runDefinition(def, "找 main", ctx);

        assertEquals(TaskStatus.COMPLETED, result.status());
        assertTrue(wm.list().isEmpty(), "非隔离模式不应创建 worktree");
        assertFalse(provider.requests.get(0).currentUserMessage().text().contains("隔离工作目录"));
    }

    private static final class ScriptedProvider implements ChatProvider {
        final String text;
        final List<ProviderRequest> requests = new CopyOnWriteArrayList<>();

        ScriptedProvider(String text) {
            this.text = text;
        }

        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
            requests.add(request);
            CompletableFuture<Void> completion = new CompletableFuture<>();
            consumer.accept(new StreamEvent.Completed(
                    new ProviderResponse("", text, List.of(), ProviderStopReason.END_TURN,
                            Usage.unknown(), null)));
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
