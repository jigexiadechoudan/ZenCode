package dev.zencode.team.command;

import dev.zencode.chat.Usage;
import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandResult;
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
import dev.zencode.team.BackendDetector;
import dev.zencode.team.SpawnRequest;
import dev.zencode.team.TeamManager;
import dev.zencode.team.TeamStore;
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
 * /team 命令测试：list / info / use / coordinator on|off / 非法子命令。
 */
class TeamCommandTest {

    @TempDir
    Path tmp;

    private TeamManager manager(boolean coordinatorEnabled) {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(completed("完成"));
            round.completion.complete(null);
        }));
        SubagentContext base = new SubagentContext(provider, null, tmp, null, null,
                ToolRegistry.createDefault(tmp), null);
        return new TeamManager(tmp, new TeamConfig(coordinatorEnabled), new TeamStore(tmp),
                null, null, base, new BackendDetector());
    }

    private TeamCommand command(TeamManager mgr, ToolRegistry registry) {
        RequestAssembler assembler = new RequestAssembler(tmp, "m",
                SystemPromptBuilder.defaults().build(), registry.schemas(Protocol.ANTHROPIC));
        return new TeamCommand(mgr, new TeamStore(tmp), registry, assembler);
    }

    private static String run(TeamCommand cmd, String args) {
        CommandResult result = cmd.handle(new CommandContext(null, "team", args));
        assertInstanceOf(CommandResult.ShowMessage.class, result);
        return ((CommandResult.ShowMessage) result).text();
    }

    @Test
    void listShowsTeamsAndCurrent() {
        TeamManager mgr = manager(false);
        mgr.createTeam("alpha");
        mgr.createTeam("beta");
        mgr.useTeam("alpha");
        String text = run(command(mgr, ToolRegistry.createDefault(tmp)), "list");
        assertTrue(text.contains("alpha"));
        assertTrue(text.contains("beta"));
        assertTrue(text.contains("←当前"));
    }

    @Test
    void listEmptyShowsHint() {
        TeamManager mgr = manager(false);
        String text = run(command(mgr, ToolRegistry.createDefault(tmp)), "list");
        assertTrue(text.contains("无团队"));
    }

    @Test
    void infoShowsRosterAndTasks() throws Exception {
        TeamManager mgr = manager(false);
        mgr.createTeam("alpha");
        mgr.spawnMember(SpawnRequest.of("explorer", "", "任务"));
        awaitIdle(mgr, "explorer");
        mgr.currentTeam().orElseThrow().board().create("任务一", "描述", List.of(), "lead");

        String text = run(command(mgr, ToolRegistry.createDefault(tmp)), "info alpha");
        assertTrue(text.contains("alpha"));
        assertTrue(text.contains("explorer"));
        assertTrue(text.contains("任务一"));
        String missing = run(command(mgr, ToolRegistry.createDefault(tmp)), "info nope");
        assertTrue(missing.contains("不存在"));
    }

    private static void awaitIdle(TeamManager mgr, String name) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            var m = mgr.currentTeam().orElseThrow().member(name).orElse(null);
            if (m != null && m.status() == dev.zencode.team.MemberStatus.IDLE) return;
            Thread.sleep(20);
        }
        fail("队员 " + name + " 未在超时内转为 IDLE");
    }

    @Test
    void useSwitchesCurrentTeam() {
        TeamManager mgr = manager(false);
        mgr.createTeam("alpha");
        mgr.createTeam("beta");
        String text = run(command(mgr, ToolRegistry.createDefault(tmp)), "use alpha");
        assertTrue(text.contains("alpha"));
        assertEquals("alpha", mgr.currentTeamName());
        String missing = run(command(mgr, ToolRegistry.createDefault(tmp)), "use nope");
        assertTrue(missing.contains("不存在"));
    }

    @Test
    void coordinatorOnRequiresConfigLock() {
        TeamManager mgr = manager(false);
        mgr.createTeam("alpha");
        String text = run(command(mgr, ToolRegistry.createDefault(tmp)), "coordinator on");
        assertTrue(text.contains("coordinator"));
        assertFalse(mgr.coordinatorActive());
    }

    @Test
    void coordinatorOnOffRoundTrip() {
        TeamManager mgr = manager(true);
        mgr.createTeam("alpha");
        ToolRegistry registry = ToolRegistry.createDefault(tmp);
        String on = run(command(mgr, registry), "coordinator on");
        assertTrue(on.contains("已激活"));
        assertTrue(mgr.coordinatorActive());
        assertFalse(registry.isEnabled("Bash"), "激活后 Bash 应被禁用");

        String off = run(command(mgr, registry), "coordinator off");
        assertTrue(off.contains("已退出"));
        assertFalse(mgr.coordinatorActive());
        assertTrue(registry.isEnabled("Bash"), "退出后 Bash 应恢复");
    }

    @Test
    void unknownSubcommandShowsUsage() {
        TeamManager mgr = manager(false);
        String text = run(command(mgr, ToolRegistry.createDefault(tmp)), "bogus");
        assertTrue(text.contains("用法"));
        String blank = run(command(mgr, ToolRegistry.createDefault(tmp)), "");
        assertTrue(blank.contains("用法"));
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
