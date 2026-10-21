package dev.zencode;

import dev.zencode.agent.*;
import dev.zencode.agent.execution.ToolInterceptorChain;
import dev.zencode.app.ConversationController;
import dev.zencode.chat.*;
import dev.zencode.config.*;
import dev.zencode.context.*;
import dev.zencode.memory.*;
import dev.zencode.permission.*;
import dev.zencode.permission.hitl.HitlBroker;
import dev.zencode.permission.rule.RuleEngine;
import dev.zencode.prompt.*;
import dev.zencode.provider.*;
import dev.zencode.session.SessionStore;
import dev.zencode.subagent.*;
import dev.zencode.team.*;
import dev.zencode.tool.*;
import dev.zencode.tui.ConversationView;
import dev.zencode.worktree.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class HarnessRuntimeRegressionTest {
    @TempDir Path directory;

    @Test
    void unexpectedProviderExceptionTerminatesAndReleasesController() throws Exception {
        ChatProvider provider = provider((request, events) -> {
            throw new IllegalArgumentException("private-provider-detail");
        });
        Conversation conversation = new Conversation();
        Agent agent = new Agent(provider, new ToolRegistry(), directory);
        AtomicReference<String> error = new AtomicReference<>();
        ConversationController controller = new ConversationController(conversation, agent, view(error));

        assertTrue(controller.submit("task"));
        awaitIdle(controller);
        assertNotNull(error.get());
        assertFalse(error.get().contains("private-provider-detail"));
        assertTrue(controller.submit("another task"));
        awaitIdle(controller);
    }

    @Test
    void compactedHistoryAndFollowingMessageSurviveRestore() {
        Conversation conversation = new Conversation();
        SessionStore store = new SessionStore(directory);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);
        manager.start();
        conversation.commit(ConversationTurn.seedTextOnly(SeedType.ENVIRONMENT, "environment"));
        for (int i = 0; i < 8; i++) conversation.commit(ConversationTurn.userTextOnly("before-" + i));
        manager.savePending();
        ChatProvider summary = provider((request, events) -> {
            events.accept(new StreamEvent.TextDelta("<summary>summary</summary>"));
            events.accept(completed("<summary>summary</summary>"));
        });
        ContextCompressor compressor = new ContextCompressor(new ToolResultSpooler(directory, ContextConfig.defaults()),
                new SummaryGenerator(summary, Duration.ofSeconds(2)), ContextConfig.defaults());

        assertTrue(compressor.compactOnce(conversation).ok());
        conversation.commit(ConversationTurn.userTextOnly("after-compact"));
        manager.savePending();
        List<ConversationTurn> expected = conversation.snapshot();
        manager.restore(manager.currentId());
        assertEquals(expected.stream().map(ConversationTurn::contents).toList(),
                conversation.snapshot().stream().map(ConversationTurn::contents).toList());
        assertEquals(SeedType.ENVIRONMENT, conversation.snapshot().getFirst().seedType());
        assertEquals("after-compact", conversation.snapshot().getLast().userMessage().text());
        conversation.commit(ConversationTurn.userTextOnly("next"));
        manager.savePending();
        assertEquals(5, store.loadValidated(manager.currentId()).turns().size());
    }

    @Test
    void sameLengthHistoryReplacementIsSavedWithoutDuplicates() {
        Conversation conversation = new Conversation();
        SessionStore store = new SessionStore(directory);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);
        manager.start();
        conversation.commit(ConversationTurn.userTextOnly("old"));
        manager.savePending();
        conversation.replaceAll(List.of(ConversationTurn.userTextOnly("new")));
        manager.savePending();
        manager.savePending();
        assertEquals(1, store.load(manager.currentId()).size());
        assertEquals("new", store.loadValidated(manager.currentId()).turns().getFirst().userMessage().text());
    }

    @Test
    void failedArchiveAppendCanBeRetriedWithoutLosingTheMessage() throws Exception {
        Conversation conversation = new Conversation();
        SessionStore store = new SessionStore(directory);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);
        manager.start();
        Path blocked = Files.createDirectory(store.sessionsDir().resolve(manager.currentId() + ".jsonl"));
        conversation.commit(ConversationTurn.userTextOnly("retry-me"));
        manager.savePending();
        Files.delete(blocked);
        manager.savePending();
        assertEquals("retry-me", store.loadValidated(manager.currentId()).turns().getFirst().userMessage().text());
    }

    @Test
    void manualMemoryExtractionUsesExistingMemoriesAndWritesResult() {
        AtomicReference<ProviderRequest> request = new AtomicReference<>();
        MemoryStore memories = new MemoryStore(directory, directory.resolve("home"), MemoryConfig.defaults());
        assertTrue(memories.write(MemoryStore.Scope.PROJECT, "existing-memory"));
        ChatProvider provider = provider((req, events) -> {
            request.set(req);
            events.accept(completed("### 项目知识\nexisting-memory\nnew-memory"));
        });
        Conversation conversation = new Conversation();
        conversation.commit(ConversationTurn.userTextOnly("remember this"));
        ConversationController controller = controller(conversation, finishedRunner(), null,
                new MemoryExtractor(provider, Duration.ofSeconds(2)), MemoryConfig.defaults());
        assertFalse(controller.memoryExtract(), "缺少存储时不能报告成功触发");
        controller.setMemoryStore(memories);

        assertTrue(controller.memoryExtract());
        assertTrue(request.get().currentUserMessage().text().contains("existing-memory"));
        assertTrue(memories.read(MemoryStore.Scope.PROJECT).contains("new-memory"));
    }

    @Test
    void automaticMemoryExtractionRunsAfterConfiguredRound() throws Exception {
        ChatProvider provider = provider((request, events) -> events.accept(completed(
                request.systemPrompt().isEmpty() ? "### 项目知识\nauto-memory" : "answer")));
        Conversation conversation = new Conversation();
        MemoryConfig config = new MemoryConfig(true, 1, 16384, 3, 24, true);
        MemoryStore memories = new MemoryStore(directory, directory.resolve("home"), config);
        SessionManager manager = new SessionManager(new SessionStore(directory), config, null, conversation);
        Agent agent = new Agent(provider, new ToolRegistry(), directory);
        ConversationController controller = controller(conversation, agent, manager,
                new MemoryExtractor(provider, Duration.ofSeconds(2)), config);
        controller.setMemoryStore(memories);

        assertTrue(controller.submit("task"));
        awaitIdle(controller);
        assertTrue(memories.read(MemoryStore.Scope.PROJECT).contains("auto-memory"));
    }

    @Test
    void memoryWriteFailureIsReportedAsFailure() throws Exception {
        Files.writeString(directory.resolve(".zencode"), "blocks directory creation");
        MemoryStore store = new MemoryStore(directory, directory.resolve("home"), MemoryConfig.defaults());
        ChatProvider provider = provider((request, events) -> events.accept(completed("### 项目知识\nnew-memory")));
        assertFalse(new MemoryExtractor(provider, Duration.ofSeconds(2))
                .extractAsync("", List.of(ConversationTurn.userTextOnly("remember")), store).join().ok());
    }

    @Test
    void restoredAndExplicitNewSessionKeepIdOnFirstSubmit() throws Exception {
        Conversation conversation = new Conversation();
        SessionStore store = new SessionStore(directory);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);
        manager.start();
        conversation.commit(ConversationTurn.userTextOnly("history"));
        manager.savePending();
        String restored = manager.currentId();
        ConversationController controller = controller(conversation, finishedRunner(), manager, null, MemoryConfig.defaults());

        assertFalse(controller.loadSession(UUID.randomUUID().toString()));
        assertTrue(controller.loadSession(restored));
        assertTrue(controller.submit("continue"));
        awaitIdle(controller);
        assertEquals(restored, manager.currentId());
        assertTrue(controller.newSession());
        String created = manager.currentId();
        assertNotEquals(restored, created);
        assertTrue(controller.submit("new task"));
        awaitIdle(controller);
        assertEquals(created, manager.currentId());
    }

    @Test
    void roundLimitRemainsRoundLimitForSubagentAndTeamMember() {
        ChatProvider provider = provider((request, events) -> events.accept(toolResponse("probe", Map.of())));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BaseTool("probe", "probe", ToolCategory.READ, ToolPermission.READ_ONLY, Map.of()) {
            protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext context) {
                return ToolResult.success(context.callId(), name(), "result");
            }
        });
        SubagentContext context = new SubagentContext(provider, null, directory, null, null, registry, null);
        AgentDefinition definition = new AgentDefinition("audit", "audit", Optional.empty(), 1,
                PermissionMode.READ_ONLY, List.of("probe"), List.of(), "audit", false,
                new AgentSource(AgentSource.Level.PROJECT, directory.resolve("audit.md")));
        assertEquals(TaskStatus.ROUND_LIMIT,
                new SubagentRunner().runDefinition(definition, "task", context).status());

        TeamRuntime runtime = new TeamRuntime(new Team("audit", "lead", 1L), directory.resolve("team"));
        TeamMember member = new TeamMember("member", "member", "", MemberStatus.RUNNING,
                directory.toString(), BackendType.INPROCESS, false, false, null, 1, 1L);
        assertEquals(TaskStatus.ROUND_LIMIT,
                new TeamMemberRunner(null).runMember(runtime, member, "task", context, List.of()).status());
    }

    @Test
    void directorySwitchChangesToolsAndPermissionBoundaryTogether() throws Exception {
        Path branch = Files.createDirectories(directory.resolve(".zencode/worktrees/branch"));
        Files.writeString(directory.resolve("sample.txt"), "root-content");
        Files.writeString(branch.resolve("sample.txt"), "branch-content");
        WorkspacePathResolver resolver = new WorkspacePathResolver(directory);
        ToolRegistry registry = ToolRegistry.createDefault(resolver);
        RuleEngine rules = new RuleEngine(null, null);
        PermissionChecker checker = new PermissionChecker(new DangerCommandDetector(), new PathSandbox(resolver),
                rules, PermissionMode.TRUST_ALL, registry);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<ProviderRequest> secondRequest = new AtomicReference<>();
        ChatProvider provider = provider((request, events) -> {
            if (requests.getAndIncrement() == 0) events.accept(toolResponse("ReadFile", Map.of("path", "sample.txt")));
            else { secondRequest.set(request); events.accept(completed("done")); }
        });
        RequestAssembler assembler = new RequestAssembler(directory, "audit", SystemPromptBuilder.defaults().build(), registry.schemas(Protocol.OPENAI));
        Agent agent = new Agent(provider, registry, directory,
                new ToolInterceptorChain(List.of(new PermissionInterceptor(checker, rules, null))), "audit", null, assembler);
        WorktreeManager manager = new WorktreeManager(directory, WorktreeConfig.defaults(), null, () -> {});
        manager.setCacheInvalidator(() -> {
            agent.refreshForNewDirectory(manager.currentDirectory());
            resolver.refreshForNewDirectory(manager.currentDirectory());
            assembler.refreshForNewDirectory(manager.currentDirectory(), null, null);
        });
        manager.setDirectoryChangeAllowed(() -> false);
        assertFalse(manager.enter("branch").success());
        assertEquals(directory, manager.currentDirectory());
        manager.setDirectoryChangeAllowed(() -> !agent.hasActiveRuns());
        assertTrue(manager.enter("branch").success());

        AgentRun run = agent.run(new Conversation(), "read", AgentRunOptions.defaults());
        String result = "";
        while (true) {
            AgentEvent event = run.pollEvent(Duration.ofSeconds(3));
            assertNotNull(event);
            if (event instanceof AgentEvent.ToolFinished finished) result = finished.result().content();
            if (event instanceof AgentEvent.Terminated) break;
        }
        assertTrue(result.contains("branch-content"), result);
        assertFalse(result.contains("root-content"), result);
        assertTrue(secondRequest.get().history().getFirst().userMessage().text().contains(branch.toString()));
        assertNotEquals(PermissionDecision.ALLOW, checker.check(new ToolCall("outside", "ReadFile",
                Map.of("path", directory.resolve("sample.txt").toString()), "", "openai")).decision());
    }

    @Test
    void subagentUsesOwnWorkspaceWithoutRebindingParentTools() throws Exception {
        Path child = Files.createDirectories(directory.resolve("child"));
        Files.writeString(directory.resolve("sample.txt"), "parent-content");
        Files.writeString(child.resolve("sample.txt"), "child-content");
        ToolRegistry parent = ToolRegistry.createDefault(directory);
        parent.disable("Bash");
        AtomicInteger rounds = new AtomicInteger();
        AtomicReference<String> read = new AtomicReference<>();
        ChatProvider provider = provider((request, events) -> {
            if (rounds.getAndIncrement() == 0) events.accept(toolResponse("ReadFile", Map.of("path", "sample.txt")));
            else {
                for (ConversationContent content : request.history().getLast().contents()) {
                    if (content instanceof ConversationContent.ToolResultBlock block) read.set(block.toolResult().result().content());
                }
                events.accept(completed("done"));
            }
        });
        SubagentContext context = new SubagentContext(provider, null, child, null, null, parent, null);
        new SubagentRunner().runFork(new Conversation(), "read", context);
        assertTrue(read.get().contains("child-content"), read.get());
        ToolResult result = parent.execute("ReadFile", Map.of("path", "sample.txt"),
                new ToolExecutionContext(directory, "parent", new java.util.concurrent.atomic.AtomicBoolean(), false));
        assertTrue(result.content().contains("parent-content"));
        assertFalse(parent.forWorkspace(child).isEnabled("Bash"));
    }

    private static ConversationController controller(Conversation conversation, AgentRunner runner, SessionManager manager,
                                                     MemoryExtractor extractor, MemoryConfig config) {
        return new ConversationController(conversation, runner, view(new AtomicReference<>()), AgentRunOptions.defaults(),
                new HitlBroker(), null, null, ContextConfig.defaults(), manager, extractor, config);
    }

    private static ConversationView view(AtomicReference<String> error) {
        return (ConversationView) Proxy.newProxyInstance(HarnessRuntimeRegressionTest.class.getClassLoader(),
                new Class<?>[]{ConversationView.class}, (proxy, method, args) -> {
                    if (method.getName().equals("showError")) error.set((String) args[0]);
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }

    private static AgentRunner finishedRunner() {
        return (conversation, message, options) -> {
            AgentRunImpl run = new AgentRunImpl(64);
            run.publishTerminated(AgentTerminationReason.COMPLETED);
            return run;
        };
    }

    private static void awaitIdle(ConversationController controller) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (controller.isBusy() && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(controller.isBusy());
    }

    private static StreamEvent completed(String text) {
        return new StreamEvent.Completed(new ProviderResponse("", text, List.of(), ProviderStopReason.END_TURN, Usage.unknown(), null));
    }

    private static StreamEvent toolResponse(String name, Map<String, Object> arguments) {
        ToolCall call = new ToolCall("call", name, arguments, "", "openai");
        return new StreamEvent.Completed(new ProviderResponse("", "", List.of(new ToolCallAssembler.AssembledCall(call, null)),
                ProviderStopReason.TOOL_USE, Usage.unknown(), null));
    }

    private static ChatProvider provider(BiConsumer<ProviderRequest, Consumer<StreamEvent>> action) {
        return new ChatProvider() {
            public String source() { return "openai"; }
            public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
                action.accept(request, consumer);
                return new StreamHandle() {
                    public void cancel() { }
                    public CompletionStage<Void> completion() { return CompletableFuture.completedFuture(null); }
                };
            }
        };
    }
}
