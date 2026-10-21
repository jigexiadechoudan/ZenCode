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
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
 * 五个顶层工具测试：成功路径与无团队/非法参数错误路径。
 */
class TeamTopLevelToolsTest {

    @TempDir
    Path tmp;

    private TeamManager manager;
    private ToolRegistry registry;
    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(completed("完成"));
            round.completion.complete(null);
        }));
        SubagentContext base = new SubagentContext(provider, null, tmp, null, null,
                ToolRegistry.createDefault(tmp), null);
        manager = new TeamManager(tmp, TeamConfig.defaults(), new TeamStore(tmp),
                null, null, base, new BackendDetector());
        registry = ToolRegistry.createDefault(tmp);
        ctx = new ToolExecutionContext(tmp, "call-1");
    }

    @AfterEach
    void stopMembers() {
        for (Team team : manager.listTeams()) manager.deleteTeam(team.name());
    }

    @Test
    void teamCreateRegistersSendMessageAndRejectsDuplicate() {
        TeamCreateTool tool = new TeamCreateTool(manager, registry);
        ToolResult r = tool.execute(Map.of("name", "alpha"), ctx);
        assertTrue(r.success(), r.content());
        assertEquals("alpha", manager.currentTeamName());
        assertTrue(registry.find("send_message").isPresent(), "创建团队后 Lead 应获得 SendMessage");
        assertTrue(registry.isEnabled("send_message"));

        ToolResult dup = tool.execute(Map.of("name", "alpha"), ctx);
        assertFalse(dup.success());
        assertTrue(dup.content().contains("已存在"));
    }

    @Test
    void teamCreateRejectsInvalidName() {
        TeamCreateTool tool = new TeamCreateTool(manager, registry);
        ToolResult r = tool.execute(Map.of("name", "../evil"), ctx);
        assertFalse(r.success());
        assertNull(manager.currentTeamName());
    }

    @Test
    void teamSpawnSpawnsMember() {
        manager.createTeam("alpha");
        TeamSpawnTool tool = new TeamSpawnTool(manager);
        ToolResult r = tool.execute(Map.of("name", "explorer", "task", "查代码",
                "backend", "inprocess", "isolation", false), ctx);
        assertTrue(r.success(), r.content());
        assertTrue(r.content().contains("explorer"));
        assertTrue(r.content().contains("agentId=m-"));
        TeamMember m = manager.currentTeam().orElseThrow().member("explorer").orElseThrow();
        assertEquals(BackendType.INPROCESS, m.backend());
    }

    @Test
    void teamSpawnWithoutTeamErrors() {
        TeamSpawnTool tool = new TeamSpawnTool(manager);
        ToolResult r = tool.execute(Map.of("name", "explorer", "task", "任务"), ctx);
        assertFalse(r.success());
        assertTrue(r.content().contains("没有团队"));
    }

    @Test
    void teamSpawnRejectsInvalidBackend() {
        manager.createTeam("alpha");
        TeamSpawnTool tool = new TeamSpawnTool(manager);
        ToolResult r = tool.execute(Map.of("name", "e", "task", "t", "backend", "bogus"), ctx);
        assertFalse(r.success());
        assertTrue(r.content().contains("未知后端"));
    }

    @Test
    void teamTerminateStopsMember() throws Exception {
        manager.createTeam("alpha");
        manager.spawnMember(SpawnRequest.of("explorer", "", "任务"));
        awaitIdle(manager, "explorer");
        TeamTerminateTool tool = new TeamTerminateTool(manager);
        ToolResult r = tool.execute(Map.of("name", "explorer"), ctx);
        assertTrue(r.success(), r.content());
        assertEquals(MemberStatus.TERMINATED,
                manager.currentTeam().orElseThrow().member("explorer").orElseThrow().status());
        ToolResult missing = tool.execute(Map.of("name", "nobody"), ctx);
        assertFalse(missing.success());
    }

    private static void awaitIdle(TeamManager mgr, String name) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            var m = mgr.currentTeam().orElseThrow().member(name).orElse(null);
            if (m != null && m.status() == MemberStatus.IDLE) return;
            Thread.sleep(20);
        }
        fail("队员 " + name + " 未在超时内转为 IDLE");
    }

    @Test
    void teamMergeWithoutIsolationMembersFails() {
        manager.createTeam("alpha");
        TeamMergeTool tool = new TeamMergeTool(manager);
        ToolResult r = tool.execute(Map.of(), ctx);
        assertFalse(r.success());
        assertTrue(r.content().contains("没有可合并"));
    }

    @Test
    void teamMergeWithoutTeamErrors() {
        TeamMergeTool tool = new TeamMergeTool(manager);
        ToolResult r = tool.execute(Map.of(), ctx);
        assertFalse(r.success());
        assertTrue(r.content().contains("没有团队"));
    }

    @Test
    void teamDeleteRemovesTeamAndUnregistersSendMessage() {
        manager.createTeam("alpha");
        registry.register(new SendMessageTool(manager.currentTeam().orElseThrow(), manager, "lead"));
        TeamDeleteTool tool = new TeamDeleteTool(manager, registry);
        ToolResult r = tool.execute(Map.of(), ctx);
        assertTrue(r.success(), r.content());
        assertNull(manager.currentTeamName());
        assertFalse(registry.find("send_message").isPresent(), "删除团队后应注销 SendMessage");
        assertTrue(manager.listTeams().isEmpty());
    }

    @Test
    void teamDeleteWithoutTeamErrors() {
        TeamDeleteTool tool = new TeamDeleteTool(manager, registry);
        ToolResult r = tool.execute(Map.of(), ctx);
        assertFalse(r.success());
        assertTrue(r.content().contains("没有团队"));
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
