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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SendMessageTool 测试：名称解析写邮箱 / 广播 / 未知名可读错误 / IDLE 队员恢复触发。
 */
class SendMessageToolTest {

    @TempDir
    Path tmp;

    private TeamManager manager(Path workDir) {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                round -> {
                    round.emit(completed("第一轮"));
                    round.completion.complete(null);
                },
                round -> {
                    round.emit(completed("第二轮"));
                    round.completion.complete(null);
                }));
        SubagentContext ctx = new SubagentContext(provider, null, workDir, null, null,
                ToolRegistry.createDefault(workDir), null);
        return new TeamManager(workDir, TeamConfig.defaults(), new TeamStore(workDir),
                null, null, ctx, new BackendDetector());
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

    private ToolResult send(TeamManager mgr, Map<String, Object> args) {
        SendMessageTool tool = new SendMessageTool(current(mgr), mgr, "lead");
        return tool.execute(args, new ToolExecutionContext(tmp, "call-1"));
    }

    @Test
    void sendByNameWritesMailbox() throws Exception {
        TeamManager mgr = manager(tmp);
        mgr.createTeam("alpha");
        mgr.spawnMember(SpawnRequest.of("explorer", "", "任务"));
        awaitIdle(mgr, "explorer", 10_000);

        ToolResult r = send(mgr, Map.of("to", "explorer", "message", "请继续"));
        assertTrue(r.success(), r.content());
        assertTrue(r.content().contains("explorer"));
        // send 触发异步 resume，等队员重新回到 IDLE 后再断言（避免线程占用临时目录）
        awaitIdle(mgr, "explorer", 10_000);
        TeamMember explorer = current(mgr).member("explorer").orElseThrow();
        List<TeamMessage> mails = current(mgr).mailbox().readAll(explorer.agentId());
        assertTrue(mails.stream().anyMatch(msg -> msg.body().contains("请继续")));
        assertEquals("lead", mails.get(0).from());
    }

    @Test
    void broadcastToAllWritesEveryMember() throws Exception {
        TeamManager mgr = manager(tmp);
        mgr.createTeam("alpha");
        mgr.spawnMember(SpawnRequest.of("explorer", "", "任务"));
        mgr.spawnMember(SpawnRequest.of("analyst", "", "任务"));
        awaitIdle(mgr, "explorer", 10_000);
        awaitIdle(mgr, "analyst", 10_000);

        ToolResult r = send(mgr, Map.of("to", "all", "message", "全员收工"));
        assertTrue(r.success(), r.content());
        assertTrue(r.content().contains("广播"));
        TeamMember a = current(mgr).member("explorer").orElseThrow();
        TeamMember b = current(mgr).member("analyst").orElseThrow();
        assertTrue(current(mgr).mailbox().readAll(a.agentId()).stream()
                .anyMatch(msg -> msg.type() == MessageType.BROADCAST));
        assertTrue(current(mgr).mailbox().readAll(b.agentId()).stream()
                .anyMatch(msg -> msg.type() == MessageType.BROADCAST));
    }

    @Test
    void unknownMemberReturnsReadableError() throws Exception {
        TeamManager mgr = manager(tmp);
        mgr.createTeam("alpha");
        mgr.spawnMember(SpawnRequest.of("explorer", "", "任务"));
        awaitIdle(mgr, "explorer", 10_000);

        ToolResult r = send(mgr, Map.of("to", "nobody", "message", "hi"));
        assertFalse(r.success());
        assertTrue(r.content().contains("未知队员"));
    }

    @Test
    void messageToIdleMemberTriggersResume() throws Exception {
        TeamManager mgr = manager(tmp);
        mgr.createTeam("alpha");
        mgr.spawnMember(SpawnRequest.of("explorer", "", "第一个任务"));
        awaitIdle(mgr, "explorer", 10_000);

        ToolResult r = send(mgr, Map.of("to", "explorer", "message", "新任务: 做第二件事"));
        assertTrue(r.success(), r.content());
        // 队员被恢复并再次运行（第二轮脚本），最终回到 IDLE
        awaitIdle(mgr, "explorer", 10_000);
        // resume 期间状态曾为 RUNNING —— 通过 transcript 行数增长间接验证（首跑 seed+1 轮，续写再 +1 轮）
        TeamMember explorer = current(mgr).member("explorer").orElseThrow();
        int lines = countLines(current(mgr).transcript().transcriptFile(explorer.agentId()));
        assertTrue(lines >= 3, "续写应追加轮次，当前行数=" + lines);
    }

    private static int countLines(Path file) {
        try {
            return (int) java.nio.file.Files.lines(file).count();
        } catch (Exception e) {
            return 0;
        }
    }

    @Test
    void invalidTypeReturnsError() throws Exception {
        TeamManager mgr = manager(tmp);
        mgr.createTeam("alpha");
        mgr.spawnMember(SpawnRequest.of("explorer", "", "任务"));
        awaitIdle(mgr, "explorer", 10_000);
        ToolResult r = send(mgr, Map.of("to", "explorer", "message", "x", "type", "bogus"));
        assertFalse(r.success());
        assertTrue(r.content().contains("未知消息类型"));
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
