package dev.zencode.agent;

import dev.zencode.agent.execution.PlanOnlyInterceptor;
import dev.zencode.agent.execution.ToolBatchContext;
import dev.zencode.agent.execution.ToolBatchExecutor;
import dev.zencode.agent.execution.ToolEventSink;
import dev.zencode.agent.execution.ToolExecutionJournal;
import dev.zencode.agent.execution.ToolExecutionInterceptor;
import dev.zencode.agent.execution.ToolInterceptorChain;
import dev.zencode.chat.ChatMessage;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Role;
import dev.zencode.config.Protocol;
import dev.zencode.context.ContextCompressor;
import dev.zencode.hook.HookContext;
import dev.zencode.hook.HookEngine;
import dev.zencode.hook.HookEvent;
import dev.zencode.prompt.RequestAssembler;
import dev.zencode.prompt.SystemPromptBuilder;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.provider.ToolCallAssembler;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolResultContent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * UI 无关的 Agent 运行入口。
 * <p>
 * 构造时注入 {@link ChatProvider}、{@link ToolRegistry}、工作目录和基础拦截器链。
 * {@link #run} 校验输入、立即返回 {@link AgentRun}，并在独立虚拟线程中执行主循环。
 * 主循环负责固定 Schema、发起单轮请求、聚合完整响应、按序执行工具批、
 * 一次提交完整轮次并决定继续或终止。只有主循环可以提交历史与发布普通事件。
 * </p>
 * <p>
 * 终止路径全部汇聚到 {@link AgentRunImpl#publishTerminated} 的 exactly-once 入口；
 * 外部取消通过共享取消信号、活动流句柄取消和中断主循环关闭活动资源。
 * </p>
 */
public final class Agent implements AgentRunner {
    private final ChatProvider provider;
    private final ToolRegistry registry;
    private volatile Path workingDirectory;
    private final ToolExecutionInterceptor interceptorChain;
    private final ToolBatchExecutor batchExecutor;
    private final String modelName;
    private final ContextCompressor compressor;
    private final RequestAssembler customAssembler;
    private final HookEngine hookEngine;
    private final AtomicInteger activeRuns = new AtomicInteger();

    public Agent(ChatProvider provider, ToolRegistry registry, Path workingDirectory,
                 ToolExecutionInterceptor interceptorChain, String modelName) {
        this(provider, registry, workingDirectory, interceptorChain, modelName, null);
    }

    public Agent(ChatProvider provider, ToolRegistry registry, Path workingDirectory,
                 ToolExecutionInterceptor interceptorChain, String modelName,
                 ContextCompressor compressor) {
        this(provider, registry, workingDirectory, interceptorChain, modelName, compressor, null);
    }

    public Agent(ChatProvider provider, ToolRegistry registry, Path workingDirectory,
                 ToolExecutionInterceptor interceptorChain, String modelName,
                 ContextCompressor compressor,
                 RequestAssembler customAssembler) {
        this(provider, registry, workingDirectory, interceptorChain, modelName, compressor, customAssembler, null);
    }

    public Agent(ChatProvider provider, ToolRegistry registry, Path workingDirectory,
                 ToolExecutionInterceptor interceptorChain, String modelName,
                 ContextCompressor compressor,
                 RequestAssembler customAssembler,
                 HookEngine hookEngine) {
        this.provider = Objects.requireNonNull(provider, "Provider 不能为空");
        this.registry = Objects.requireNonNull(registry, "工具注册中心不能为空");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "工作目录不能为空");
        this.interceptorChain = Objects.requireNonNull(interceptorChain, "拦截器链不能为空");
        this.batchExecutor = new ToolBatchExecutor(registry);
        this.modelName = modelName == null || modelName.isBlank() ? "未知" : modelName;
        this.compressor = compressor;
        this.customAssembler = customAssembler;
        this.hookEngine = hookEngine;
    }

    /** 使用默认放行拦截器链与未知模型名构造 Agent。 */
    public Agent(ChatProvider provider, ToolRegistry registry, Path workingDirectory) {
        this(provider, registry, workingDirectory, ToolInterceptorChain.empty(), "未知");
    }

    /** 装配期启用持久化执行日志；写入失败时副作用工具会拒绝执行。 */
    public void setExecutionJournal(ToolExecutionJournal journal) {
        batchExecutor.setJournal(journal);
    }

    /** 包括已发布取消/终止事件但主循环尚未退出的运行。 */
    public boolean hasActiveRuns() {
        return activeRuns.get() != 0;
    }

    public void refreshForNewDirectory(Path directory) {
        if (hasActiveRuns()) throw new IllegalStateException("Agent 仍在运行，不能切换工作目录");
        workingDirectory = directory.toAbsolutePath().normalize();
    }

    /**
     * 启动一次 Agent 运行。
     *
     * @param conversation 持久会话，已提交轮次保留，本轮由主循环按需提交
     * @param userMessage 本次运行的用户消息
     * @param options 运行选项
     * @return 立即返回的运行句柄；主循环在独立虚拟线程执行
     */
    public AgentRun run(Conversation conversation, String userMessage, AgentRunOptions options) {
        Objects.requireNonNull(conversation, "会话不能为空");
        Objects.requireNonNull(userMessage, "用户消息不能为空");
        if (userMessage.isBlank()) {
            throw new IllegalArgumentException("用户消息不能为空");
        }
        Objects.requireNonNull(options, "运行选项不能为空");

        AgentRunImpl run = new AgentRunImpl(options.eventBufferCapacity());
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<StreamHandle> activeHandle = new AtomicReference<>();

        run.setCancelCallback(() -> {
            cancelled.set(true);
            StreamHandle handle = activeHandle.getAndSet(null);
            if (handle != null) {
                try {
                    handle.cancel();
                } catch (RuntimeException ignored) {
                    // 取消 Provider 失败不影响取消语义
                }
            }
        });

        Protocol protocol = toProtocol(provider.source());
        List<Map<String, Object>> schema = registry.schemas(protocol);
        RequestAssembler assembler = customAssembler != null
                ? customAssembler
                : new RequestAssembler(workingDirectory, modelName,
                        SystemPromptBuilder.defaults().build(), schema);
        ToolExecutionInterceptor chain = buildChain(options.planOnly());

        activeRuns.incrementAndGet();
        try {
            Thread loop = Thread.ofVirtual().name("zencode-agent-loop").unstarted(() -> {
                try {
                    mainLoop(run, conversation, userMessage, options, cancelled,
                            activeHandle, assembler, chain);
                } catch (RuntimeException failure) {
                    if (!cancelled.get()) {
                        run.publish(new AgentEvent.Error("Agent 执行失败，请检查模型配置与运行环境"));
                    }
                } finally {
                    run.publishTerminated(cancelled.get()
                            ? AgentTerminationReason.CANCELLED : AgentTerminationReason.ERROR);
                    activeRuns.decrementAndGet();
                }
            });
            run.setProducerThread(loop);
            loop.start();
        } catch (RuntimeException | Error failure) {
            activeRuns.decrementAndGet();
            throw failure;
        }
        return run;
    }

    private void mainLoop(AgentRunImpl run, Conversation conversation, String userMessage,
                          AgentRunOptions options, AtomicBoolean cancelled,
                          AtomicReference<StreamHandle> activeHandle, RequestAssembler assembler,
                          ToolExecutionInterceptor chain) {
        run.publish(new AgentEvent.UserMessage(userMessage));
        runHook(HookEvent.TURN_START, userMessage, null);
        boolean firstRound = true;
        int round = 1;

        try {
            while (true) {
                if (cancelled.get()) return;

                if (compressor != null) {
                    compressor.prepareForRequest(conversation);
                }

                ProviderRequest request = assembler.assemble(conversation, round,
                        options.planOnly(), firstRound ? userMessage : null);
                runHook(HookEvent.PRE_SEND, firstRound ? userMessage : null, null);

                RoundResult result = runRound(run, request, options, round, cancelled, activeHandle);
                runHook(HookEvent.POST_RECEIVE, null, null);
                if (cancelled.get()) return;
                switch (result.outcome()) {
                    case CANCELLED -> {
                        return;
                    }
                    case FAILED, TIMEOUT, PROTOCOL_ERROR -> {
                        runHook(HookEvent.ERROR, null, result.safeMessage());
                        run.publish(new AgentEvent.Error(result.safeMessage()));
                        run.publishTerminated(AgentTerminationReason.ERROR);
                        return;
                    }
                    case COMPLETED -> {
                        // 继续处理完整响应
                    }
                }

                ProviderResponse response = result.response();
                if (cancelled.get()) return;

                ProviderStopReason stopReason = response.stopReason();
                if (stopReason == ProviderStopReason.MAX_TOKENS || stopReason == ProviderStopReason.OTHER) {
                    String message = "模型因结束原因终止: "
                            + (stopReason == ProviderStopReason.MAX_TOKENS ? "达到 token 上限" : "不可恢复的其他结束原因");
                    runHook(HookEvent.ERROR, null, message);
                    run.publish(new AgentEvent.Error(message));
                    run.publishTerminated(AgentTerminationReason.ERROR);
                    return;
                }

                if (response.hasToolCalls()) {
                    List<ToolResult> toolResults = executeBatch(response.toolCalls(), options, run, round, chain, cancelled);
                    if (cancelled.get()) return;
                    commitToolTurn(conversation, response, toolResults, firstRound, userMessage);
                    run.noteRoundCommitted();
                    run.publish(new AgentEvent.RoundUsageCommitted(round, response.usage()));

                    if (round >= options.maxRounds()) {
                        run.publish(new AgentEvent.FinalReply("已达到最大轮数上限，停止继续调用工具。"));
                        run.publishTerminated(AgentTerminationReason.ROUND_LIMIT);
                        return;
                    }
                    firstRound = false;
                    round++;
                } else {
                    if (response.text().isBlank()) {
                        String message = "模型未给出有效回复";
                        runHook(HookEvent.ERROR, null, message);
                        run.publish(new AgentEvent.Error(message));
                        run.publishTerminated(AgentTerminationReason.ERROR);
                        return;
                    }
                    commitTextTurn(conversation, response, firstRound, userMessage);
                    run.noteRoundCommitted();
                    run.publish(new AgentEvent.RoundUsageCommitted(round, response.usage()));
                    run.publish(new AgentEvent.FinalReply(response.text()));
                    run.publishTerminated(AgentTerminationReason.COMPLETED);
                    return;
                }
            }
        } finally {
            runHook(HookEvent.TURN_END, null, null);
        }
    }

    /** 触发非工具类 Hook 事件；引擎为空时跳过。 */
    private void runHook(HookEvent event, String message, String error) {
        if (hookEngine != null) {
            hookEngine.runHooks(event, new HookContext(
                    event.name().toLowerCase(java.util.Locale.ROOT), null, java.util.Map.of(),
                    null, message, error));
        }
    }

    /**
     * 单轮模型响应聚合。
     * <p>
     * Provider 回调只向内部桥接队列入队，不直接修改会话。主循环按滑动静默期限
     * 消费事件并转发 thinking/text 增量；等待唯一 Completed 或 Failed 终端事件。
     * 超时、失败或 Provider 完成但无终端事件时返回相应结果。
     * </p>
     */
    private RoundResult runRound(AgentRunImpl run, ProviderRequest request, AgentRunOptions options,
                                 int round, AtomicBoolean cancelled,
                                 AtomicReference<StreamHandle> activeHandle) {
        ArrayBlockingQueue<StreamEvent> bridge = new ArrayBlockingQueue<>(options.eventBufferCapacity());
        AtomicBoolean terminalSeen = new AtomicBoolean(false);
        AtomicBoolean roundClosed = new AtomicBoolean(false);

        Consumer<StreamEvent> consumer = event -> {
            if (event instanceof StreamEvent.Completed || event instanceof StreamEvent.Failed) {
                terminalSeen.set(true);
            }
            try {
                // 主循环取消/超时后不再消费队列；reader 必须能自行解除背压，
                // 否则关闭 HTTP 输入流也无法让 physicalCompletion 完成。
                while (!roundClosed.get() && !cancelled.get()) {
                    if (bridge.offer(event, POLL_CHUNK_NANOS, java.util.concurrent.TimeUnit.NANOSECONDS)) return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        StreamHandle handle = null;
        Duration idle = options.responseIdleTimeout();
        long deadline = System.nanoTime() + idle.toNanos();

        try {
            run.publish(new AgentEvent.ModelStarted(round));
            handle = provider.stream(request, consumer);
            activeHandle.set(handle);
            while (true) {
                if (cancelled.get()) {
                    return RoundResult.cancelled();
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return RoundResult.timeout("模型响应超时（" + idle.toMillis() + " 毫秒无事件）");
                }
                StreamEvent event = bridge.poll(Math.min(remaining, POLL_CHUNK_NANOS), java.util.concurrent.TimeUnit.NANOSECONDS);
                if (event == null) {
                    if (handle.completion().toCompletableFuture().isDone()
                            && bridge.isEmpty() && !terminalSeen.get()) {
                        return RoundResult.protocolError("Provider 完成但没有终端事件");
                    }
                    continue;
                }
                deadline = System.nanoTime() + idle.toNanos();
                switch (event) {
                    case StreamEvent.ThinkingDelta delta ->
                            run.publish(new AgentEvent.ThinkingDelta(delta.text(), round));
                    case StreamEvent.TextDelta delta ->
                            run.publish(new AgentEvent.TextDelta(delta.text(), round));
                    case StreamEvent.ToolCallStart start -> {
                        // 工具调用事件由批处理器发布 ToolStarted/ToolFinished
                    }
                    case StreamEvent.ToolCallDelta delta -> {
                        // 仅用于实时展示，Agent 事件协议不消费参数增量
                    }
                    case StreamEvent.Completed completed -> {
                        return RoundResult.completed(completed.response());
                    }
                    case StreamEvent.Failed failed -> {
                        return RoundResult.failed(failed.safeMessage());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (cancelled.get()) {
                return RoundResult.cancelled();
            }
            return RoundResult.failed("模型请求被中断");
        } finally {
            roundClosed.set(true);
            // 同外部取消回调竞争句柄所有权，避免重复取消。正常终端事件后也释放
            // 仍保持连接的 SSE；迟到回调会因 roundClosed 被丢弃。
            if (handle != null && activeHandle.compareAndSet(handle, null)) {
                try {
                    handle.cancel();
                } catch (RuntimeException ignored) {
                    // 清理失败不能覆盖已确定的响应/取消结果。
                }
            }
        }
    }

    /** 按序执行工具批并发布工具事件。 */
    private List<ToolResult> executeBatch(List<ToolCallAssembler.AssembledCall> calls,
                                          AgentRunOptions options, AgentRunImpl run, int round,
                                          ToolExecutionInterceptor chain, AtomicBoolean cancelled) {
        ToolEventSink sink = new ToolEventSink() {
            @Override
            public void started(String callId, String toolName, String safeSummary, int r) {
                run.publish(new AgentEvent.ToolStarted(callId, toolName, safeSummary, r));
            }

            @Override
            public void finished(String callId, String toolName, ToolResult result, int r) {
                run.publish(new AgentEvent.ToolFinished(callId, toolName, result, r));
            }
        };
        ToolBatchContext context = new ToolBatchContext(workingDirectory, cancelled,
                options.planOnly(), chain, sink, round);
        return batchExecutor.execute(calls, context);
    }

    private void commitToolTurn(Conversation conversation, ProviderResponse response,
                                List<ToolResult> toolResults, boolean firstRound, String userMessage) {
        List<ConversationContent> contents = new ArrayList<>();
        if (firstRound) {
            contents.add(new ConversationContent.UserText(userMessage));
        }
        appendAssistantContent(contents, response);
        for (ToolCallAssembler.AssembledCall assembled : response.toolCalls()) {
            contents.add(new ConversationContent.ToolCallBlock(assembled.toolCall()));
        }
        for (ToolResult result : toolResults) {
            contents.add(new ConversationContent.ToolResultBlock(
                    new ToolResultContent(result, result.callId(), result.toolName())));
        }
        conversation.commit(new ConversationTurn(contents, response.providerState(), response.usage()));
    }

    private void commitTextTurn(Conversation conversation, ProviderResponse response,
                                boolean firstRound, String userMessage) {
        List<ConversationContent> contents = new ArrayList<>();
        if (firstRound) {
            contents.add(new ConversationContent.UserText(userMessage));
        }
        appendAssistantContent(contents, response);
        conversation.commit(new ConversationTurn(contents, response.providerState(), response.usage()));
    }

    private static void appendAssistantContent(List<ConversationContent> contents, ProviderResponse response) {
        if (!response.thinking().isBlank()) {
            contents.add(new ConversationContent.ThinkingText(response.thinking()));
        }
        if (!response.text().isBlank()) {
            contents.add(new ConversationContent.AssistantText(response.text()));
        }
    }

    /** 构造运行使用的拦截器链：plan-only 时在基础链前追加只读拦截器。 */
    private ToolExecutionInterceptor buildChain(boolean planOnly) {
        if (!planOnly) {
            return interceptorChain;
        }
        return new ToolInterceptorChain(List.of(new PlanOnlyInterceptor(registry), interceptorChain));
    }

    private static Protocol toProtocol(String source) {
        if ("anthropic".equals(source)) return Protocol.ANTHROPIC;
        if ("openai".equals(source)) return Protocol.OPENAI;
        throw new IllegalArgumentException("未知 Provider 来源: " + source);
    }

    private static final long POLL_CHUNK_NANOS = 50_000_000L; // 50ms 轮询片，兼顾响应与定时检测

    private enum Outcome {
        COMPLETED,
        FAILED,
        TIMEOUT,
        PROTOCOL_ERROR,
        CANCELLED
    }

    private record RoundResult(Outcome outcome, ProviderResponse response, String safeMessage) {
        static RoundResult completed(ProviderResponse response) {
            return new RoundResult(Outcome.COMPLETED, response, null);
        }

        static RoundResult failed(String message) {
            return new RoundResult(Outcome.FAILED, null, message);
        }

        static RoundResult timeout(String message) {
            return new RoundResult(Outcome.TIMEOUT, null, message);
        }

        static RoundResult protocolError(String message) {
            return new RoundResult(Outcome.PROTOCOL_ERROR, null, message);
        }

        static RoundResult cancelled() {
            return new RoundResult(Outcome.CANCELLED, null, null);
        }
    }
}
