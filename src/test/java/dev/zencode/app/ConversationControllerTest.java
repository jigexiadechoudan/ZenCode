package dev.zencode.app;

import dev.zencode.agent.AgentEvent;
import dev.zencode.agent.AgentRun;
import dev.zencode.agent.AgentRunImpl;
import dev.zencode.agent.AgentRunOptions;
import dev.zencode.agent.AgentRunner;
import dev.zencode.agent.AgentTerminationReason;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Usage;
import dev.zencode.config.ContextConfig;
import dev.zencode.context.ContextCompressor;
import dev.zencode.context.SummaryGenerator;
import dev.zencode.context.ToolResultSpooler;
import dev.zencode.permission.PermissionChecker;
import dev.zencode.permission.DangerCommandDetector;
import dev.zencode.permission.PathSandbox;
import dev.zencode.permission.PermissionMode;
import dev.zencode.permission.rule.RuleEngine;
import dev.zencode.permission.hitl.HitlBroker;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.hitl.HitlRequest;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolResultContent;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.WorkspacePathResolver;
import dev.zencode.permission.PermissionDecision;
import dev.zencode.tui.ConversationView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T18：Controller 作为 Agent 事件适配器。FakeAgent 驱动纯文本、thinking、错误、
 * 取消、重复提交与终止清理，断言视图事件顺序和 busy 状态。
 */
class ConversationControllerTest {

    @Test
    void pureTextFlowMapsEventsAndClearsBusy() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view,
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64));

        assertTrue(controller.submit("问题"));
        assertTrue(controller.isBusy());
        assertFalse(controller.submit("第二个问题"), "忙碌时应拒绝并发提交");

        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("问题"));
        run.publish(new AgentEvent.ThinkingDelta("思考片段", 1));
        run.publish(new AgentEvent.TextDelta("回", 1));
        run.publish(new AgentEvent.TextDelta("答", 1));
        run.publish(new AgentEvent.FinalReply("回答"));
        run.publishTerminated(AgentTerminationReason.COMPLETED);

        awaitIdle(controller);
        assertEquals("问题", view.userMessages.get(0));
        assertEquals("思考片段", view.thinking);
        assertEquals("回答", view.answer);
        assertEquals(1, view.finishedMessages);
        assertFalse(controller.isBusy());
        assertTrue(controller.submit("第二轮"), "终止后应可再次提交");
    }

    @Test
    void errorEventShowsErrorAndClearsBusy() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view);

        assertTrue(controller.submit("错误请求"));
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("错误请求"));
        run.publish(new AgentEvent.Error("模型响应超时"));
        run.publishTerminated(AgentTerminationReason.ERROR);

        awaitIdle(controller);
        assertEquals("模型响应超时", view.error);
        assertFalse(controller.isBusy());
    }

    @Test
    void cancellationAbortsAndClearsBusy() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view);

        assertTrue(controller.submit("取消请求"));
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("取消请求"));

        controller.cancel();
        awaitIdle(controller);
        assertTrue(run.isCancelRequested(), "取消应调用 AgentRun.cancel");
        assertTrue(view.aborted);
        assertFalse(controller.isBusy());
    }

    @Test
    void toolEventsMapToSafeSummaries() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view);

        assertTrue(controller.submit("工具请求"));
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("工具请求"));
        run.publish(new AgentEvent.ToolStarted("call-1", "ReadFile", "path=readme.md", 1));
        run.publish(new AgentEvent.ToolFinished("call-1", "ReadFile",
                ToolResult.success("call-1", "ReadFile", "文件内容"), 1));
        run.publish(new AgentEvent.FinalReply("完成"));
        run.publishTerminated(AgentTerminationReason.COMPLETED);

        awaitIdle(controller);
        assertTrue(view.toolEvents.stream().anyMatch(e -> e.startsWith("tool_start:ReadFile:path=readme.md")));
        assertTrue(view.toolEvents.stream().anyMatch(e -> e.startsWith("tool_completed:ReadFile")));
        assertTrue(view.toolEvents.stream().anyMatch(e -> e.contains("文件内容")), "短内容作为安全摘要展示");
    }

    @Test
    void longResultTailIsNeverShown() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view);

        assertTrue(controller.submit("长结果"));
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("长结果"));
        run.publish(new AgentEvent.ToolStarted("call-1", "ReadFile", "path=readme.md", 1));
        run.publish(new AgentEvent.ToolFinished("call-1", "ReadFile",
                ToolResult.success("call-1", "ReadFile", "A".repeat(500) + "TAIL_MARKER_XYZ"), 1));
        run.publish(new AgentEvent.FinalReply("完成"));
        run.publishTerminated(AgentTerminationReason.COMPLETED);

        awaitIdle(controller);
        assertFalse(view.toolEvents.stream().anyMatch(e -> e.contains("TAIL_MARKER_XYZ")), "长结果尾部绝不渲染");
    }

    @Test
    void failedToolEventMapsToFailure() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view);

        assertTrue(controller.submit("失败工具"));
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("失败工具"));
        run.publish(new AgentEvent.ToolStarted("call-2", "WriteFile", "path=a.txt", 1));
        run.publish(new AgentEvent.ToolFinished("call-2", "WriteFile",
                ToolResult.error("call-2", "WriteFile", "权限不足"), 1));
        run.publish(new AgentEvent.FinalReply("已处理"));
        run.publishTerminated(AgentTerminationReason.COMPLETED);

        awaitIdle(controller);
        assertTrue(view.toolEvents.stream().anyMatch(e -> e.startsWith("tool_failed:WriteFile")));
    }

    @Test
    void permissionRequestedRendersDialogAndResolveUnblocksBroker() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        HitlBroker broker = new HitlBroker();
        ConversationController controller = new ConversationController(conversation, agent, view,
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64), broker);

        assertTrue(controller.submit("确认请求"));
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("确认请求"));
        run.publish(new AgentEvent.PermissionRequested("call-9", "Bash", "del x", "命中询问规则"));
        run.publish(new AgentEvent.FinalReply("已处理"));
        run.publishTerminated(AgentTerminationReason.COMPLETED);

        awaitIdle(controller);
        assertNotNull(view.permissionRequest);
        assertEquals("Bash", view.permissionRequest.toolName());
        assertEquals("del x", view.permissionRequest.safeSummary());
        assertEquals("命中询问规则", view.permissionRequest.reason());

        // resolvePermission 转发到共享 broker，解除被阻塞的 ask
        HitlRequest ask = new HitlRequest("call-9", "Bash", "del x", "命中询问规则");
        Thread askThread = Thread.ofVirtual().start(() -> broker.ask(ask));
        awaitPending(broker);
        assertTrue(controller.resolvePermission("call-9", HitlDecision.ALLOW_SESSION));
        askThread.join(2000);
        assertFalse(askThread.isAlive(), "决策回传后 ask 应解除阻塞");
    }

    private static void awaitPending(HitlBroker broker) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && broker.pendingCount() == 0) {
            Thread.sleep(10);
        }
    }

    @Test
    void hitlDialogRendersOnConsumerThreadNotAgentThread() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        HitlBroker broker = new HitlBroker();
        ConversationController controller = new ConversationController(conversation, agent, view,
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64), broker);

        assertTrue(controller.submit("写文件"));
        AgentRunImpl run = agent.lastRun();

        // 模拟 Agent 线程：在拦截器中发起 ask 并阻塞等待用户决策
        AtomicReference<HitlDecision> decision = new AtomicReference<>();
        Thread agentThread = Thread.ofVirtual().name("agent-loop").start(() ->
                decision.set(broker.ask(new HitlRequest("call-1", "WriteFile", "cli.md",
                        "规则与权限模式均未自动决策"))));

        awaitPermissionRendered(view);
        assertNotNull(view.permissionRequest, "对话框应在消费线程渲染");
        assertNotEquals("agent-loop", view.renderThreadName,
                "渲染绝不能发生在 Agent 线程（Windows 终端跨线程刷新会卡死）");

        assertTrue(controller.resolvePermission("call-1", HitlDecision.ALLOW_ONCE));
        agentThread.join(2000);
        assertFalse(agentThread.isAlive(), "决策回传后 ask 应解除阻塞");
        assertEquals(HitlDecision.ALLOW_ONCE, decision.get());
    }

    private static void awaitPermissionRendered(RecordingView view) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && view.permissionRequest == null) {
            Thread.sleep(10);
        }
    }

    @Test
    void roundLimitTerminationClearsBusyWithFinalReply() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view);

        assertTrue(controller.submit("上限请求"));
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("上限请求"));
        run.publish(new AgentEvent.FinalReply("已达到最大轮数上限"));
        run.publishTerminated(AgentTerminationReason.ROUND_LIMIT);

        awaitIdle(controller);
        assertEquals("已达到最大轮数上限", view.answer);
        assertEquals(1, view.finishedMessages);
        assertFalse(controller.isBusy());
    }

    @Test
    void lateEventsAfterTerminationAreIgnored() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view);

        assertTrue(controller.submit("终止后迟到"));
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("终止后迟到"));
        run.publishTerminated(AgentTerminationReason.CANCELLED);
        awaitIdle(controller);

        run.publish(new AgentEvent.TextDelta("迟到", 1));
        run.publish(new AgentEvent.FinalReply("迟到回复"));
        Thread.sleep(100);
        assertEquals("", view.answer, "终止后迟到事件应被忽略");
        assertFalse(controller.isBusy());
    }

    @Test
    void setPlanOnlyFlipsState() {
        ConversationController controller = new ConversationController(
                new Conversation(), new FakeAgent(), new RecordingView());
        assertFalse(controller.planOnly());
        controller.setPlanOnly(true);
        assertTrue(controller.planOnly());
        controller.setPlanOnly(false);
        assertFalse(controller.planOnly());
    }

    @Test
    void planOnlyFlowsIntoSubmittedOptions() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view,
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64));

        controller.setPlanOnly(true);
        assertTrue(controller.submit("计划模式请求"));
        AgentRunImpl run = agent.lastRun();
        run.publish(new AgentEvent.UserMessage("计划模式请求"));
        run.publish(new AgentEvent.FinalReply("完成"));
        run.publishTerminated(AgentTerminationReason.COMPLETED);
        awaitIdle(controller);
        assertTrue(agent.lastOptions.planOnly(), "提交给 Agent 的 options 应携带 planOnly=true");
    }

    @Test
    void switchPermissionModeUpdatesStateAndChecker() throws Exception {
        PermissionChecker checker = new PermissionChecker(
                new DangerCommandDetector(),
                new PathSandbox(new WorkspacePathResolver(Path.of("."))),
                new RuleEngine(Path.of("no-project-rules.yaml"), Path.of("no-user-rules.yaml")),
                PermissionMode.DEFAULT, ToolRegistry.createDefault(Path.of(".")));

        ConversationController controller = new ConversationController(
                new Conversation(), new FakeAgent(), new RecordingView(),
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64), new HitlBroker(), checker);

        assertEquals(PermissionMode.DEFAULT, controller.permissionMode());
        controller.switchPermissionMode(PermissionMode.LOCKED);
        assertEquals(PermissionMode.LOCKED, controller.permissionMode());
        assertEquals(PermissionMode.LOCKED, checker.mode(), "切换应转发到权限检查器");

        // 工具决策双层验证：LOCKED 下写操作被拒绝，切回 DEFAULT 恢复（转 HITL）
        ToolCall write = new ToolCall("c1", "WriteFile",
                java.util.Map.of("path", "a.txt", "content", "x"), "{}", "anthropic");
        assertEquals(PermissionDecision.DENY_BLOCKED, checker.check(write).decision(),
                "LOCKED 下写操作应拒绝");
        controller.switchPermissionMode(PermissionMode.DEFAULT);
        assertEquals(PermissionDecision.REQUEST_HITL, checker.check(write).decision(),
                "DEFAULT 下写操作转 HITL 而非拒绝");

        // 校验器为 null 时仅存状态
        ConversationController noChecker = new ConversationController(
                new Conversation(), new FakeAgent(), new RecordingView());
        noChecker.switchPermissionMode(PermissionMode.TRUST_ALL);
        assertEquals(PermissionMode.TRUST_ALL, noChecker.permissionMode());
    }

    private static void awaitIdle(ConversationController controller) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (controller.isBusy() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(controller.isBusy(), "应在超时前释放 busy");
    }

    @TempDir
    Path tempDir;

    @Test
    void compactWhileIdleReportsTokenChange() {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ScriptedProvider provider = new ScriptedProvider();
        ContextConfig config = new ContextConfig(50, 0.8, 4096, 32768, 3);
        ContextCompressor compressor = new ContextCompressor(
                new ToolResultSpooler(tempDir, config),
                new SummaryGenerator(provider, Duration.ofSeconds(2)),
                config);
        ConversationController controller = new ConversationController(conversation, agent, view,
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64), new HitlBroker(),
                null, compressor);

        conversation.commit(ConversationTurn.seedTextOnly(dev.zencode.chat.SeedType.ENVIRONMENT, "环境快照轮次"));
        conversation.commit(new ConversationTurn(List.of(
                new ConversationContent.UserText("读取文件"),
                new ConversationContent.ToolResultBlock(new ToolResultContent(
                        ToolResult.success("c1", "read", "文件内容" + "a".repeat(300)), "c1", "read"))),
                null, null));

        ContextCompressor.CompactResult result = controller.compact();

        assertTrue(result.ok(), result.message());
        assertTrue(result.beforeTokens() > 0);
        assertTrue(result.afterTokens() > 0);
        assertEquals(3, conversation.size(), "压缩后应为 [环境][摘要][边界]");
    }

    @Test
    void compactWhileBusyRejected() throws Exception {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ContextCompressor compressor = new ContextCompressor(
                new ToolResultSpooler(tempDir, ContextConfig.defaults()),
                new SummaryGenerator(new ScriptedProvider(), Duration.ofSeconds(2)),
                ContextConfig.defaults());
        ConversationController controller = new ConversationController(conversation, agent, view,
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64), new HitlBroker(),
                null, compressor);

        assertTrue(controller.submit("任务"));
        ContextCompressor.CompactResult result = controller.compact();
        assertFalse(result.ok());
        assertTrue(result.message().contains("忙碌"));
    }

    @Test
    void compactWithoutCompressorReportsNotEnabled() {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view);

        ContextCompressor.CompactResult result = controller.compact();
        assertFalse(result.ok());
        assertTrue(result.message().contains("未启用"));
    }

    @Test
    void exposesContextEstimateAndWindow() {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ContextConfig config = new ContextConfig(42, 0.8, 4096, 32768, 3);
        ConversationController controller = new ConversationController(conversation, agent, view,
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64), new HitlBroker(),
                null, null, config);

        assertEquals(42, controller.contextWindowTokens());
        assertEquals(0, controller.estimatedContextTokens());

        conversation.commit(ConversationTurn.userTextOnly("消息一"));
        conversation.commit(ConversationTurn.userTextOnly("消息二消息三"));
        long estimate = controller.estimatedContextTokens();
        assertTrue(estimate > 0, "对话提交后估算应大于 0");
    }

    @Test
    void contextWindowDefaultsWhenNotInjected() {
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view);
        assertEquals(200_000L, controller.contextWindowTokens());
    }

    private static final class FakeAgent implements AgentRunner {
        final CopyOnWriteArrayList<AgentRunImpl> runs = new CopyOnWriteArrayList<>();
        volatile AgentRunOptions lastOptions;

        @Override
        public AgentRun run(Conversation conversation, String userMessage, AgentRunOptions options) {
            AgentRunImpl run = new AgentRunImpl(options.eventBufferCapacity());
            runs.add(run);
            lastOptions = options;
            return run;
        }

        AgentRunImpl lastRun() {
            return runs.get(runs.size() - 1);
        }
    }

    private static final class ScriptedProvider implements ChatProvider {
        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            consumer.accept(new StreamEvent.TextDelta("<summary>压缩摘要</summary>"));
            consumer.accept(new StreamEvent.Completed(new ProviderResponse(
                    "", "", List.of(), ProviderStopReason.END_TURN, null, null)));
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
            return "test";
        }
    }

    private static final class RecordingView implements ConversationView {
        final List<String> userMessages = new CopyOnWriteArrayList<>();
        final List<String> toolEvents = new CopyOnWriteArrayList<>();
        volatile String thinking = "";
        volatile String answer = "";
        volatile String error = "";
        volatile HitlRequest permissionRequest;
        volatile int finishedMessages;
        volatile boolean aborted;
        volatile boolean busy;

        @Override public void showUserMessage(String s) { userMessages.add(s); }
        @Override public void beginAssistantMessage() {}
        @Override public void appendThinking(String s) { thinking += s; }
        @Override public void appendAnswer(String s) { answer += s; }
        @Override public void finishAssistantMessage() { finishedMessages++; }
        @Override public void markAssistantAborted() { aborted = true; }
        @Override public void showError(String s) { error = s; }
        @Override public void showUsage(Usage c, Usage t) {}
        @Override public void setBusy(boolean b) { busy = b; }
        @Override public void showToolEvent(String type, String toolName, String callId, String summary) {
            toolEvents.add(type + ":" + toolName + ":" + summary);
        }
        @Override public void showPermissionRequest(HitlRequest request) {
            permissionRequest = request;
            renderThreadName = Thread.currentThread().getName();
        }
        @Override public boolean resolvePermission(HitlDecision decision) { return false; }
        volatile String renderThreadName;
    }
}
