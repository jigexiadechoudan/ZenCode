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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 团队生命周期测试：create/spawn（in-process 真实跑完）/IDLE 通知/terminate/delete/
 * 重名拒绝/非 git 隔离降级/消息与续写/coordinator 双锁。
 */
class TeamManagerTest {

    @TempDir
    Path tmp;

    private final java.util.List<TeamManager> managers = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void stopMembers() {
        for (TeamManager manager : managers) {
            for (Team team : manager.listTeams()) manager.deleteTeam(team.name());
        }
    }

    private TeamManager manager(Path workDir, boolean coordinatorEnabled) {
        TeamStore store = new TeamStore(workDir);
        ScriptedProvider provider = new ScriptedProvider(List.of(
                round -> {
                    round.emit(completed("第一轮结论"));
                    round.completion.complete(null);
                },
                round -> {
                    round.emit(completed("第二轮结论"));
                    round.completion.complete(null);
                }));
        SubagentContext ctx = new SubagentContext(provider, null, workDir, null, null,
                ToolRegistry.createDefault(workDir), null);
        TeamManager manager = new TeamManager(workDir, new TeamConfig(coordinatorEnabled), store,
                null, null, ctx, new BackendDetector());
        managers.add(manager);
        return manager;
    }

    private static TeamRuntime current(TeamManager mgr) {
        return mgr.currentTeam().orElseThrow();
    }

    private static void awaitIdle(TeamManager mgr, String name, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TeamMember m = current(mgr).member(name).orElse(null);
            if (m != null && m.status() == MemberStatus.IDLE) {
                // 状态已置 IDLE，但 persist 文件写入可能尚未收尾（Windows 句柄延迟），稍候
                Thread.sleep(150);
                return;
            }
            Thread.sleep(20);
        }
        fail("队员 " + name + " 未在超时内转为 IDLE，状态="
                + current(mgr).member(name).map(TeamMember::status).orElse(null));
    }

    @Test
    void createTeamPersistsAndRejectsDuplicate() {
        TeamManager mgr = manager(tmp, false);
        Team team = mgr.createTeam("alpha");
        assertEquals("alpha", team.name());
        assertEquals("alpha", mgr.currentTeamName());
        assertTrue(mgr.listTeams().stream().anyMatch(t -> t.name().equals("alpha")));
        assertThrows(TeamException.class, () -> mgr.createTeam("alpha"));
    }

    @Test
    void createTeamRejectsInvalidName() {
        TeamManager mgr = manager(tmp, false);
        assertThrows(TeamException.class, () -> mgr.createTeam("../evil"));
        assertThrows(TeamException.class, () -> mgr.createTeam("a/b"));
        assertThrows(TeamException.class, () -> mgr.createTeam(""));
    }

    @Test
    void spawnMemberRunsToIdleAndNotifies() throws Exception {
        TeamManager mgr = manager(tmp, false);
        mgr.createTeam("alpha");
        AtomicReference<String> notice = new AtomicReference<>();
        mgr.setNotifier(notice::set);

        TeamMember m = mgr.spawnMember(new SpawnRequest("explorer", "", "查代码",
                BackendType.INPROCESS, false, false, null, 0));
        assertEquals("explorer", m.name());
        assertNotNull(m.agentId());
        assertTrue(m.agentId().startsWith("m-"));
        assertEquals(MemberStatus.RUNNING, m.status());

        awaitIdle(mgr, "explorer", 10_000);
        // notifier 在 IDLE 落盘之后触发，轮询等待（消除竞态）
        long deadline = System.currentTimeMillis() + 5000;
        while (notice.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertNotNull(notice.get());
        assertTrue(notice.get().contains("explorer"));
        // Lead 邮箱收到 lifecycle(IDLE) 邮件
        List<TeamMessage> leadMails = current(mgr).mailbox().readAll("lead");
        assertTrue(leadMails.stream().anyMatch(msg ->
                msg.type() == MessageType.LIFECYCLE && "IDLE".equals(msg.protocol().get("event"))));
        // 状态落盘
        TeamStore.StoredTeam stored = new TeamStore(tmp).load("alpha").orElseThrow();
        assertEquals(MemberStatus.IDLE, stored.roster().get(0).status());
    }

    @Test
    void spawnRejectsDuplicateMemberName() {
        TeamManager mgr = manager(tmp, false);
        mgr.createTeam("alpha");
        mgr.spawnMember(SpawnRequest.of("explorer", "", "任务"));
        assertThrows(TeamException.class, () -> mgr.spawnMember(SpawnRequest.of("explorer", "", "任务")));
    }

    @Test
    void terminateMemberMarksTerminated() throws Exception {
        TeamManager mgr = manager(tmp, false);
        mgr.createTeam("alpha");
        mgr.spawnMember(SpawnRequest.of("explorer", "", "任务"));
        // 等队员线程结束（避免占用临时目录）
        awaitIdle(mgr, "explorer", 10_000);
        assertTrue(mgr.terminateMember("explorer"));
        TeamMember m = current(mgr).member("explorer").orElseThrow();
        assertEquals(MemberStatus.TERMINATED, m.status());
        assertFalse(mgr.terminateMember("explorer"), "重复终止返回 false");
        assertThrows(TeamException.class, () -> mgr.terminateMember("nobody"));
    }

    @Test
    void deleteTeamCleansUp() {
        TeamManager mgr = manager(tmp, false);
        mgr.createTeam("alpha");
        mgr.spawnMember(SpawnRequest.of("explorer", "", "任务"));
        TeamRuntime runtime = current(mgr);
        assertTrue(mgr.deleteTeam("alpha"));
        assertFalse(runtime.hasActiveWork(), "删除文件前必须等待队员与 Agent 收尾");
        assertNull(mgr.currentTeamName());
        assertFalse(Files.exists(tmp.resolve(".zencode").resolve("teams").resolve("alpha")));
        assertTrue(mgr.listTeams().isEmpty());
        assertFalse(mgr.deleteTeam("alpha"), "重复删除返回 false");
    }

    @Test
    void useTeamSwitchesCurrent() {
        TeamManager mgr = manager(tmp, false);
        mgr.createTeam("alpha");
        mgr.createTeam("beta");
        assertEquals("beta", mgr.currentTeamName());
        mgr.useTeam("alpha");
        assertEquals("alpha", mgr.currentTeamName());
        assertThrows(TeamException.class, () -> mgr.useTeam("nope"));
    }

    @Test
    void nonGitIsolationDegradesToSharedDir() {
        // tmp 不是 git 仓库 → isolation=true 应降级共享目录
        TeamManager mgr = manager(tmp, false);
        mgr.createTeam("alpha");
        TeamMember m = mgr.spawnMember(new SpawnRequest("explorer", "", "任务",
                BackendType.INPROCESS, true, false, null, 0));
        assertEquals(tmp.toString(), m.workDir());
        assertTrue(m.isolation());
    }

    @Test
    void coordinatorRequiresConfigLock() {
        // 配置锁缺失 → enableCoordinator 抛错
        TeamManager mgr = manager(tmp, false);
        mgr.createTeam("alpha");
        assertThrows(TeamException.class, mgr::enableCoordinator);
        assertFalse(mgr.coordinatorActive());

        // 配置锁齐备 → 激活成功
        TeamManager enabled = manager(tmp, true);
        enabled.createTeam("beta");
        assertTrue(enabled.enableCoordinator());
        assertTrue(enabled.coordinatorActive());
        enabled.disableCoordinator();
        assertFalse(enabled.coordinatorActive());
    }

    @Test
    void sendMessageToIdleMemberResumes() throws Exception {
        TeamManager mgr = manager(tmp, false);
        mgr.createTeam("alpha");
        mgr.spawnMember(SpawnRequest.of("explorer", "", "第一个任务"));
        awaitIdle(mgr, "explorer", 10_000);

        String result = mgr.sendMessage(current(mgr), "lead", "explorer", MessageType.TEXT,
                "新任务", "继续做第二个任务", java.util.Map.of());
        assertTrue(result.contains("explorer"));
        // 续写触发：队员再次运行（第二轮脚本）后回到 IDLE
        awaitIdle(mgr, "explorer", 10_000);
        // 队员邮箱有新消息
        TeamMember explorer = current(mgr).member("explorer").orElseThrow();
        List<TeamMessage> mails = current(mgr).mailbox().readAll(explorer.agentId());
        assertTrue(mails.stream().anyMatch(msg -> msg.subject().contains("新任务")),
                "队员邮箱应收到新任务消息");
    }

    @Test
    void sendMessageUnknownMemberThrows() {
        TeamManager mgr = manager(tmp, false);
        mgr.createTeam("alpha");
        assertThrows(TeamException.class, () -> mgr.sendMessage(current(mgr), "lead",
                "nobody", MessageType.TEXT, "x", "y", java.util.Map.of()));
    }

    @Test
    void sendMessageBroadcastWritesAllMembers() throws Exception {
        TeamManager mgr = manager(tmp, false);
        mgr.createTeam("alpha");
        mgr.spawnMember(SpawnRequest.of("explorer", "", "任务"));
        awaitIdle(mgr, "explorer", 10_000);

        String result = mgr.sendMessage(current(mgr), "lead", "all", MessageType.BROADCAST,
                "全员注意", "收工", java.util.Map.of());
        assertTrue(result.contains("广播"));
        // 队员邮箱收到广播（readNew 会推进 marker，这里 readAll 看总数）
        TeamMember explorer = current(mgr).member("explorer").orElseThrow();
        List<TeamMessage> mails = current(mgr).mailbox().readAll(explorer.agentId());
        assertTrue(mails.stream().anyMatch(msg -> msg.type() == MessageType.BROADCAST));
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
