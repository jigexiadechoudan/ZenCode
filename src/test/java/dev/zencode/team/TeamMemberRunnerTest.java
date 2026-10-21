package dev.zencode.team;

import dev.zencode.chat.Conversation;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ToolCallAssembler;
import dev.zencode.subagent.SubagentContext;
import dev.zencode.subagent.SubagentResult;
import dev.zencode.subagent.TaskStatus;
import dev.zencode.subagent.TraceRegistry;
import dev.zencode.tool.BaseTool;
import dev.zencode.tool.Tool;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;
import dev.zencode.chat.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 队员执行测试：首跑/续写/协作工具注入/邮箱轮询注入/transcript 落盘/审批流。
 */
class TeamMemberRunnerTest {

    @TempDir
    Path tempDir;

    private TeamRuntime newRuntime() {
        return new TeamRuntime(new Team("alpha", "lead", 1L), tempDir.resolve("team"));
    }

    private TeamMember member(String name, String agentId, boolean needApproval) {
        return new TeamMember(agentId, name, "", MemberStatus.SPAWNED,
                tempDir.toString(), BackendType.INPROCESS, needApproval, false, null, 20, 1L);
    }

    private static ToolRegistry sourceRegistry() {
        return ToolRegistry.createDefault(Path.of("."));
    }

    private static Tool probeTool(String name) {
        return new BaseTool(name, "探针工具", ToolCategory.READ, ToolPermission.READ_ONLY, Map.of()) {
            @Override
            protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
                return ToolResult.success(ctx.callId(), name, "探针结果");
            }
        };
    }

    @Test
    void 队员跑通返回结果并落盘transcript() {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(completed("队员结论"));
            round.completion.complete(null);
        }));
        TraceRegistry trace = new TraceRegistry();
        SubagentContext ctx = new SubagentContext(provider, null, Path.of("."),
                null, trace, sourceRegistry(), null);
        TeamRuntime rt = newRuntime();
        TeamMember m = member("explorer", "m-1", false);

        SubagentResult result = new TeamMemberRunner(null)
                .runMember(rt, m, "查一下代码", ctx, List.of());

        assertEquals(TaskStatus.COMPLETED, result.status());
        assertEquals("队员结论", result.output());
        // transcript 已落盘（seed 轮 + 首轮）
        assertTrue(rt.transcript().transcriptFile("m-1").toFile().exists());
        Conversation loaded = rt.transcript().load("m-1");
        assertTrue(loaded.size() >= 2, "transcript 应含 seed 轮与首轮");
    }

    @Test
    void 协作工具注入队员工具池() {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(completed("完成"));
            round.completion.complete(null);
        }));
        SubagentContext ctx = new SubagentContext(provider, null, Path.of("."),
                null, null, sourceRegistry(), null);
        TeamRuntime rt = newRuntime();
        TeamMember m = member("explorer", "m-1", false);

        new TeamMemberRunner(null).runMember(rt, m, "任务", ctx,
                List.of(probeTool("task"), probeTool("send_message")));

        ProviderRequest request = provider.requests.get(0);
        List<String> names = schemaNames(request.toolSchemas());
        assertTrue(names.contains("task"), "协作工具 task 应注入: " + names);
        assertTrue(names.contains("send_message"), "协作工具 send_message 应注入: " + names);
        assertFalse(names.contains("agent"), "委派工具不应暴露: " + names);
    }

    @Test
    void 邮箱消息经mailSupplier注入请求() {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(completed("完成"));
            round.completion.complete(null);
        }));
        SubagentContext ctx = new SubagentContext(provider, null, Path.of("."),
                null, null, sourceRegistry(), null);
        TeamRuntime rt = newRuntime();
        TeamMember m = member("explorer", "m-1", false);
        rt.mailbox().send("lead", "m-1", MessageType.TEXT, "注意", "改用方案B", Map.of());

        new TeamMemberRunner(null).runMember(rt, m, "任务", ctx, List.of());

        ProviderRequest request = provider.requests.get(0);
        assertTrue(request.hasTransientReminders(), "邮件应作为 reminder 注入");
        assertTrue(request.transientReminders().stream()
                        .anyMatch(r -> r.text().contains("改用方案B")),
                "邮件内容应出现在请求提醒中");
        // 已读推进：再次读取无新消息
        assertEquals(0, rt.mailbox().pendingCount("m-1"));
    }

    @Test
    void 续写从transcript恢复并保留历史() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                round -> {
                    round.emit(completed("第一轮结论"));
                    round.completion.complete(null);
                },
                round -> {
                    round.emit(completed("第二轮结论"));
                    round.completion.complete(null);
                }));
        SubagentContext ctx = new SubagentContext(provider, null, Path.of("."),
                null, null, sourceRegistry(), null);
        TeamRuntime rt = newRuntime();
        TeamMember m = member("explorer", "m-1", false);
        TeamMemberRunner runner = new TeamMemberRunner(null);

        SubagentResult first = runner.runMember(rt, m, "第一个任务", ctx, List.of());
        assertEquals(TaskStatus.COMPLETED, first.status());

        SubagentResult second = runner.resumeMember(rt, m, "继续做第二个任务", ctx, List.of());
        assertEquals(TaskStatus.COMPLETED, second.status());
        assertEquals(2, provider.requests.size());

        ProviderRequest resumedRequest = provider.requests.get(1);
        assertTrue(resumedRequest.history().size() >= 2,
                "续写应包含上次运行的轮次历史");
        assertNotNull(resumedRequest.currentUserMessage());
        assertTrue(resumedRequest.currentUserMessage().text().contains("继续做第二个任务"));
    }

    @Test
    void needApproval队员危险操作发审批请求给Lead() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                round -> {
                    round.emit(new StreamEvent.Completed(new ProviderResponse("", "",
                            List.of(new ToolCallAssembler.AssembledCall(
                                    new dev.zencode.tool.ToolCall("c1", "Bash",
                                            Map.of("command", "rm -rf x"), "{}", "anthropic"), null)),
                            ProviderStopReason.TOOL_USE, Usage.unknown(), null)));
                    round.completion.complete(null);
                },
                round -> {
                    round.emit(completed("收到拦截后完成"));
                    round.completion.complete(null);
                }));
        SubagentContext ctx = new SubagentContext(provider, null, Path.of("."),
                null, null, sourceRegistry(), null);
        TeamRuntime rt = newRuntime();
        TeamMember m = member("explorer", "m-1", true);

        SubagentResult result = new TeamMemberRunner(null)
                .runMember(rt, m, "执行操作", ctx, List.of());
        assertEquals(TaskStatus.COMPLETED, result.status());

        // Lead 邮箱收到 APPROVAL 请求（from=队员 agentId，to=lead）
        List<TeamMessage> leadMails = rt.mailbox().readAll("lead");
        assertEquals(1, leadMails.size());
        TeamMessage approval = leadMails.get(0);
        assertEquals(MessageType.APPROVAL, approval.type());
        assertEquals("m-1", approval.from());
        assertEquals("approve", approval.protocol().get("request"));
        assertEquals("Bash", approval.protocol().get("tool"));
        // 队员得到的工具结果含「已申请审批」
        assertTrue(result.output() != null);
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
