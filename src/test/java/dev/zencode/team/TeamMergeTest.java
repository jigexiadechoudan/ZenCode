package dev.zencode.team;

import dev.zencode.chat.Usage;
import dev.zencode.config.TeamConfig;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.subagent.SubagentContext;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.worktree.GitCommand;
import dev.zencode.worktree.WorktreeConfig;
import dev.zencode.worktree.WorktreeManager;
import dev.zencode.worktree.WorktreeSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 收敛合并测试（真实 git 仓库 + isolation worktree）：
 * 正常分支合并 / 文本与二进制冲突均 abort 回滚上报。
 */
class TeamMergeTest {

    @TempDir
    Path tmp;

    private static Path initRepo(Path dir) throws Exception {
        Files.createDirectories(dir);
        GitCommand.run(dir, "init");
        Files.writeString(dir.resolve("a.txt"), "hello\n", StandardCharsets.UTF_8);
        GitCommand.run(dir, "add", "a.txt");
        GitCommand.run(dir, "-c", "user.email=test@test.com",
                "-c", "user.name=test", "commit", "-m", "init");
        return dir;
    }

    private static void commit(Path dir, String message) {
        GitCommand.run(dir, "-c", "user.email=test@test.com",
                "-c", "user.name=test", "commit", "-m", message);
    }

    private TeamManager manager(Path repo, WorktreeManager wtm) {
        TeamStore store = new TeamStore(repo);
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(completed("完成"));
            round.completion.complete(null);
        }));
        SubagentContext ctx = new SubagentContext(provider, null, repo, null, null,
                ToolRegistry.createDefault(repo), wtm);
        return new TeamManager(repo, TeamConfig.defaults(), store, wtm, null, ctx, new BackendDetector());
    }

    private static TeamRuntime current(TeamManager mgr) {
        return mgr.currentTeam().orElseThrow();
    }

    private static void awaitIdle(TeamManager mgr, String name, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TeamMember m = current(mgr).member(name).orElse(null);
            if (m != null && m.status() == MemberStatus.IDLE) return;
            Thread.sleep(20);
        }
        fail("队员 " + name + " 未在超时内转为 IDLE");
    }

    private static String branchOf(WorktreeManager wtm, TeamMember m) {
        return wtm.list().stream()
                .filter(info -> info.path().equals(Path.of(m.workDir())))
                .findFirst().map(dev.zencode.worktree.WorktreeInfo::branch)
                .orElseThrow(() -> new AssertionError("worktree 分支未找到"));
    }

    @Test
    void isolationMemberBranchMergedBackToMain() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        WorktreeManager wtm = new WorktreeManager(repo, WorktreeConfig.defaults(),
                new WorktreeSessionStore(repo), () -> {});
        TeamManager mgr = manager(repo, wtm);
        mgr.createTeam("alpha");

        TeamMember m = mgr.spawnMember(new SpawnRequest("explorer", "", "任务",
                BackendType.INPROCESS, true, false, null, 0));
        String branch = branchOf(wtm, m);
        // 队员在隔离 worktree 改文件并提交
        Path wtDir = Path.of(m.workDir());
        Files.writeString(wtDir.resolve("a.txt"), "hello\nfrom explorer\n", StandardCharsets.UTF_8);
        GitCommand.run(wtDir, "add", "a.txt");
        commit(wtDir, "explorer 改动");

        awaitIdle(mgr, "explorer", 10_000);
        MergeResult result = mgr.mergeTeam();
        assertTrue(result.success(), result.message());
        assertTrue(result.mergedBranches().contains(branch), result.message());
        assertTrue(result.rolledBack().isEmpty(), result.message());
        // 主分支已含队员改动
        String content = Files.readString(repo.resolve("a.txt"), StandardCharsets.UTF_8);
        assertTrue(content.contains("from explorer"), "主分支应包含队员改动: " + content);
    }

    @Test
    void textConflictIsAbortedForManualResolution() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        WorktreeManager wtm = new WorktreeManager(repo, WorktreeConfig.defaults(),
                new WorktreeSessionStore(repo), () -> {});
        TeamManager mgr = manager(repo, wtm);
        mgr.createTeam("alpha");

        TeamMember m = mgr.spawnMember(new SpawnRequest("explorer", "", "任务",
                BackendType.INPROCESS, true, false, null, 0));
        String branch = branchOf(wtm, m);
        // 队员分支改同一行
        Path wtDir = Path.of(m.workDir());
        Files.writeString(wtDir.resolve("a.txt"), "conflict from explorer\n", StandardCharsets.UTF_8);
        GitCommand.run(wtDir, "add", "a.txt");
        commit(wtDir, "explorer 冲突改动");
        awaitIdle(mgr, "explorer", 10_000);

        // 主分支也改同一行 → 制造冲突
        Files.writeString(repo.resolve("a.txt"), "conflict from main\n", StandardCharsets.UTF_8);
        GitCommand.run(repo, "add", "a.txt");
        commit(repo, "main 冲突改动");

        MergeResult result = mgr.mergeTeam();
        assertFalse(result.success(), result.message());
        assertTrue(result.conflicts().contains(branch), result.message());
        assertTrue(result.rolledBack().contains(branch), result.message());
        String content = Files.readString(repo.resolve("a.txt"), StandardCharsets.UTF_8);
        assertEquals("conflict from main\n", content.replace("\r\n", "\n"), "回滚后主分支内容应保持原样");
        assertFalse(GitCommand.run(repo, "rev-parse", "--verify", "MERGE_HEAD").ok());
    }

    @Test
    void unresolvableConflictRollsBackAndReports() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        WorktreeManager wtm = new WorktreeManager(repo, WorktreeConfig.defaults(),
                new WorktreeSessionStore(repo), () -> {});
        TeamManager mgr = manager(repo, wtm);
        mgr.createTeam("alpha");

        TeamMember m = mgr.spawnMember(new SpawnRequest("explorer", "", "任务",
                BackendType.INPROCESS, true, false, null, 0));
        String branch = branchOf(wtm, m);
        // 队员分支写入非法 UTF-8 二进制文件
        Path wtDir = Path.of(m.workDir());
        Files.write(wtDir.resolve("bin.dat"), new byte[]{0x00, (byte) 0xFF, 0x00});
        GitCommand.run(wtDir, "add", "bin.dat");
        commit(wtDir, "explorer 二进制改动");
        awaitIdle(mgr, "explorer", 10_000);

        // 主分支写入不同二进制内容 → 冲突且无法文本解决
        Files.write(repo.resolve("bin.dat"), new byte[]{0x00, (byte) 0xFE, 0x00});
        GitCommand.run(repo, "add", "bin.dat");
        commit(repo, "main 二进制改动");

        MergeResult result = mgr.mergeTeam();
        assertFalse(result.success(), result.message());
        assertTrue(result.rolledBack().contains(branch), "不可解决应回滚: " + result.message());
        assertTrue(result.conflicts().contains(branch), result.message());
        // abort 后仓库不在合并状态
        var mergeHead = GitCommand.run(repo, "rev-parse", "--verify", "MERGE_HEAD");
        assertFalse(mergeHead.ok(), "回滚后不应处于合并状态");
    }

    @Test
    void noIsolationMembersReportsFailure() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        TeamManager mgr = manager(repo, null);
        mgr.createTeam("alpha");
        mgr.spawnMember(SpawnRequest.of("explorer", "", "任务"));
        // 等队员线程结束（避免占用临时目录）
        awaitIdle(mgr, "explorer", 10_000);
        MergeResult result = mgr.mergeTeam();
        assertFalse(result.success());
        assertTrue(result.message().contains("没有可合并"));
    }

    // ---- fake provider ----

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
