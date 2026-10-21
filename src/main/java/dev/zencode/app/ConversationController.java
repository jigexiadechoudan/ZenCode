package dev.zencode.app;

import dev.zencode.agent.AgentEvent;
import dev.zencode.agent.AgentRun;
import dev.zencode.agent.AgentRunner;
import dev.zencode.agent.AgentRunOptions;
import dev.zencode.agent.AgentTerminationReason;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Usage;
import dev.zencode.config.ContextConfig;
import dev.zencode.context.ContextCompressor;
import dev.zencode.context.TokenEstimator;
import dev.zencode.hook.HookContext;
import dev.zencode.hook.HookEngine;
import dev.zencode.hook.HookEvent;
import dev.zencode.memory.MemoryConfig;
import dev.zencode.memory.MemoryExtractor;
import dev.zencode.memory.MemoryStore;
import dev.zencode.memory.SessionManager;
import dev.zencode.permission.PermissionChecker;
import dev.zencode.permission.PermissionMode;
import dev.zencode.permission.hitl.HitlBroker;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.hitl.HitlRequest;
import dev.zencode.session.SessionMeta;
import dev.zencode.skill.ActiveSkills;
import dev.zencode.skill.SkillContextPolicy;
import dev.zencode.skill.SkillDefinition;
import dev.zencode.skill.SkillMode;
import dev.zencode.skill.SkillRegistry;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolResultContent;
import dev.zencode.tool.ToolSummary;
import dev.zencode.tui.ConversationView;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 事件适配器。
 * <p>
 * 接收用户提交，调用 {@link AgentRunner#run} 创建运行并启动事件消费虚拟线程，
 * 把 {@link AgentEvent} 按顺序映射到 {@link ConversationView}。不再维护 Provider 流、
 * 待提交内容、单工具执行或本地历史提交。{@link AgentEvent.Terminated} 统一清理 busy。
 * 工具参数与结果只使用安全摘要。
 * </p>
 */
public final class ConversationController {
    private final Conversation conversation;
    private final AgentRunner agent;
    private final ConversationView view;
    private final AgentRunOptions options;
    private final HitlBroker hitlBroker;
    private final PermissionChecker permissionChecker;
    private final ContextCompressor compressor;
    private final ContextConfig contextConfig;
    private volatile boolean planOnly;
    private volatile PermissionMode currentPermissionMode;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile AgentRun activeRun;
    private boolean textStreamed;

    // Skill 技能系统（T8 装配期经 setSkillRegistry 注入）
    private volatile SkillRegistry skillRegistry;
    private volatile ActiveSkills activeSkills;
    private String forkFinalText;

    // 跨会话记忆系统
    private final SessionManager sessionManager;
    private final MemoryExtractor memoryExtractor;
    private final MemoryConfig memoryConfig;
    private volatile MemoryStore memoryStore;
    private volatile java.nio.file.Path workingDirectory;
    private CompletableFuture<MemoryExtractor.ExtractResult> memoryExtraction;
    private volatile boolean sessionStarted;

    // Hook 系统（装配期经 setHookEngine 注入，可为空）
    private volatile HookEngine hookEngine;

    public ConversationController(Conversation conversation, AgentRunner agent, ConversationView view) {
        this(conversation, agent, view, AgentRunOptions.defaults());
    }

    public ConversationController(Conversation conversation, AgentRunner agent, ConversationView view,
                                  AgentRunOptions options) {
        this(conversation, agent, view, options, new HitlBroker());
    }

    public ConversationController(Conversation conversation, AgentRunner agent, ConversationView view,
                                  AgentRunOptions options, HitlBroker hitlBroker) {
        this(conversation, agent, view, options, hitlBroker, null);
    }

    public ConversationController(Conversation conversation, AgentRunner agent, ConversationView view,
                                  AgentRunOptions options, HitlBroker hitlBroker,
                                  PermissionChecker permissionChecker) {
        this(conversation, agent, view, options, hitlBroker, permissionChecker, null);
    }

    public ConversationController(Conversation conversation, AgentRunner agent, ConversationView view,
                                  AgentRunOptions options, HitlBroker hitlBroker,
                                  PermissionChecker permissionChecker, ContextCompressor compressor) {
        this(conversation, agent, view, options, hitlBroker, permissionChecker, compressor, null);
    }

    public ConversationController(Conversation conversation, AgentRunner agent, ConversationView view,
                                  AgentRunOptions options, HitlBroker hitlBroker,
                                  PermissionChecker permissionChecker, ContextCompressor compressor,
                                  ContextConfig contextConfig) {
        this(conversation, agent, view, options, hitlBroker, permissionChecker, compressor, contextConfig,
                null, null, null);
    }

    public ConversationController(Conversation conversation, AgentRunner agent, ConversationView view,
                                  AgentRunOptions options, HitlBroker hitlBroker,
                                  PermissionChecker permissionChecker, ContextCompressor compressor,
                                  ContextConfig contextConfig,
                                  SessionManager sessionManager,
                                  MemoryExtractor memoryExtractor,
                                  MemoryConfig memoryConfig) {
        this.conversation = conversation;
        this.agent = agent;
        this.view = view;
        this.options = options;
        this.hitlBroker = hitlBroker;
        this.permissionChecker = permissionChecker;
        this.compressor = compressor;
        this.contextConfig = contextConfig == null ? ContextConfig.defaults() : contextConfig;
        this.sessionManager = sessionManager;
        this.memoryExtractor = memoryExtractor;
        this.memoryConfig = memoryConfig == null ? MemoryConfig.defaults() : memoryConfig;
        this.planOnly = options.planOnly();
        this.currentPermissionMode = permissionChecker == null ? null : permissionChecker.mode();
        // 确认请求不能直接在 Agent 线程渲染 UI：Windows 终端跨线程刷新会卡死。
        // 改为把请求发布到事件队列，由消费线程与其他视图更新一致地渲染确认框，
        // 从而确保屏幕只被一个渲染线程（消费者）访问。
        hitlBroker.setRequestListener(request -> {
            AgentRun run = activeRun;
            if (run != null) {
                run.publish(new AgentEvent.PermissionRequested(request.callId(),
                        request.toolName(), request.safeSummary(), request.reason()));
            }
        });
    }

    /** 提交用户消息；忙碌或空文本时拒绝。 */
    public boolean submit(String text) {
        if (text == null || text.isBlank() || !busy.compareAndSet(false, true)) return false;
        view.setBusy(true);
        try {
            // 首次提交时启动会话并触发 session_start
            if (!sessionStarted) {
                sessionStarted = true;
                if (sessionManager != null && sessionManager.currentId() == null) {
                    sessionManager.start();
                }
                runHook(HookEvent.SESSION_START, text, null);
            }
            AgentRunOptions runOptions = new AgentRunOptions(planOnly,
                    options.maxRounds(), options.responseIdleTimeout(), options.eventBufferCapacity());
            AgentRun run = agent.run(conversation, text, runOptions);
            activeRun = run;
            Thread.ofVirtual().name("zencode-controller-consumer").start(() -> consume(run));
            return true;
        } catch (RuntimeException e) {
            view.showError("请求启动失败");
            finishBusy();
            return true;
        }
    }

    /** 当前是否处于计划模式（plan-only）。 */
    public boolean planOnly() {
        return planOnly;
    }

    /** 运行时切换计划模式，对下一次提交生效。 */
    public void setPlanOnly(boolean planOnly) {
        this.planOnly = planOnly;
    }

    /** 当前生效的权限模式；未设置时按 DEFAULT 处理。 */
    public PermissionMode permissionMode() {
        return currentPermissionMode == null ? PermissionMode.DEFAULT : currentPermissionMode;
    }

    /** 运行时切换权限模式，立即对后续工具调用生效；不写回配置文件。 */
    public void switchPermissionMode(PermissionMode mode) {
        this.currentPermissionMode = mode == null ? PermissionMode.DEFAULT : mode;
        if (permissionChecker != null) {
            permissionChecker.setMode(currentPermissionMode);
        }
    }

    public void cancel() {
        AgentRun run = activeRun;
        if (run != null) {
            run.cancel();
        }
    }

    /** 返回共享的 HITL 调度器，供 PermissionInterceptor 与 UI 回传复用。 */
    public HitlBroker hitlBroker() {
        return hitlBroker;
    }

    /**
     * 回传用户对某条确认请求的决策，唤醒被阻塞的权限检查。
     *
     * @return 存在对应挂起请求且成功回传返回 true
     */
    public boolean resolvePermission(String callId, HitlDecision decision) {
        return hitlBroker.resolve(callId, decision);
    }

    /**
     * 手动触发上下文压缩。仅空闲时执行；忙碌或未启用时返回失败结果。
     * 同步执行，返回压缩前后的 Token 变化。
     */
    public ContextCompressor.CompactResult compact() {
        if (compressor == null) {
            return new ContextCompressor.CompactResult(0, 0, "", false, "未启用上下文管理");
        }
        if (busy.get()) {
            return new ContextCompressor.CompactResult(0, 0, "", false, "忙碌中，无法压缩");
        }
        return compressor.compactOnce(conversation);
    }

    /** 当前对话历史（含工具结果）的近似 Token 估算，与压缩口径一致。 */
    public long estimatedContextTokens() {
        return TokenEstimator.estimateHistory(conversation.snapshot());
    }

    /** 上下文窗口上限（context.window_tokens，缺省 200000）。 */
    public long contextWindowTokens() {
        return contextConfig.windowTokens();
    }

    public void close() {
        cancel();
        if (sessionStarted) {
            runHook(HookEvent.SESSION_END, null, null);
        }
        if (sessionManager != null) {
            sessionManager.savePending();
        }
        if (memoryExtractor != null && memoryConfig != null && memoryConfig.enabled()) {
            triggerMemoryExtraction();
            CompletableFuture<MemoryExtractor.ExtractResult> pending;
            synchronized (this) { pending = memoryExtraction; }
            if (pending != null) {
                try { pending.get(30, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) {
                    pending.cancel(true);
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                    pending.cancel(true);
                }
            }
        }
    }

    /** 装配期注入 Hook 引擎；为空时不触发任何 Hook。 */
    public void setHookEngine(HookEngine hookEngine) {
        this.hookEngine = hookEngine;
        if (compressor != null) {
            compressor.setHookEngine(hookEngine);
        }
    }

    /** 触发非工具类 Hook 事件；引擎为空时跳过。 */
    private void runHook(HookEvent event, String message, String error) {
        if (hookEngine != null) {
            hookEngine.runHooks(event, new HookContext(
                    event.name().toLowerCase(java.util.Locale.ROOT), null,
                    java.util.Map.of(), null, message, error));
        }
    }

    public boolean isBusy() {
        return busy.get();
    }

    // ---- 跨会话记忆系统命令 ----

    /** 当前会话 ID。 */
    public String currentSessionId() {
        return sessionManager != null ? sessionManager.currentId() : null;
    }

    /** 列出全部会话。 */
    public List<SessionMeta> listSessions() {
        return sessionManager != null ? sessionManager.list() : List.of();
    }

    /** 恢复指定会话。忙碌时拒绝。 */
    public boolean loadSession(String id) {
        if (busy.get() || sessionManager == null) return false;
        if (sessionManager.list().stream().noneMatch(meta -> meta.id().equals(id))) return false;
        sessionManager.savePending();
        sessionManager.restore(id);
        return true;
    }

    /** 删除指定会话。忙碌时拒绝。 */
    public boolean deleteSession(String id) {
        if (busy.get() || sessionManager == null) return false;
        try {
            return sessionManager.delete(id);
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** 新建会话。忙碌时拒绝。 */
    public boolean newSession() {
        if (busy.get() || sessionManager == null) return false;
        sessionManager.savePending();
        sessionManager.beginNew();
        return true;
    }

    // ---- Skill 技能系统 ----

    /** 装配期注入 Skill 注册中心，并建立会话级激活列表。 */
    public void setSkillRegistry(SkillRegistry registry) {
        this.skillRegistry = registry;
        this.activeSkills = new ActiveSkills();
    }

    /** 装配期注入 Skill 注册中心与共享激活列表（与 RequestAssembler/拦截器同源）。 */
    public void setSkillSystem(SkillRegistry registry, ActiveSkills activeSkills) {
        this.skillRegistry = registry;
        this.activeSkills = activeSkills;
    }

    /** 清空当前会话激活的 Skill 列表。 */
    public void clearActiveSkills() {
        if (activeSkills != null) {
            activeSkills.clear();
        }
    }

    /**
     * 提交 Skill：INLINE 激活并沿用主对话提交；FORK 用携带历史的独立对话执行。
     *
     * @return 结果文本
     */
    public String submitSkill(String skillName, String args) {
        if (skillRegistry == null || activeSkills == null) {
            return "Skill 系统未启用";
        }
        var found = skillRegistry.find(skillName);
        if (found.isEmpty()) {
            return "未知 Skill: " + skillName;
        }
        SkillDefinition skill = found.get();
        if (skill.mode() == SkillMode.FORK) {
            return startFork(skill, args);
        }
        activeSkills.activate(skill, args);
        if (!submit(args)) {
            activeSkills.deactivate(skillName);
            return "提交失败（可能忙碌中）";
        }
        return "已激活 Skill " + skill.name() + " 并提交";
    }

    /** 启动 fork 执行：独立对话携带历史，后台虚拟线程消费事件。 */
    private String startFork(SkillDefinition skill, String args) {
        if (!busy.compareAndSet(false, true)) {
            return "忙碌中，无法执行 fork";
        }
        view.setBusy(true);
        try {
            Conversation forkConv = new Conversation();
            SkillContextPolicy context = skill.context();
            List<ConversationTurn> snapshot = conversation.snapshot();
            switch (context.type()) {
                case FULL -> snapshot.forEach(forkConv::commit);
                case RECENT -> {
                    int from = Math.max(0, snapshot.size() - context.recentCount());
                    for (int i = from; i < snapshot.size(); i++) {
                        forkConv.commit(snapshot.get(i));
                    }
                }
                case NONE -> { /* 空历史 */ }
            }
            String prompt = skill.bodyWith(args);
            AgentRunOptions runOptions = new AgentRunOptions(false,
                    options.maxRounds(), options.responseIdleTimeout(), options.eventBufferCapacity());
            AgentRun run = agent.run(forkConv, prompt, runOptions);
            activeRun = run;
            Thread.ofVirtual().name("zencode-controller-fork").start(() -> consumeFork(run));
            return "已开始 fork 执行 Skill " + skill.name();
        } catch (RuntimeException e) {
            finishBusy();
            return "fork 启动失败";
        }
    }

    /** fork 事件消费循环：与主对话 consume 隔离，不触碰会话存档。 */
    private void consumeFork(AgentRun run) {
        try {
            while (true) {
                AgentEvent event = run.takeEvent();
                boolean terminated;
                try {
                    terminated = handleFork(event);
                } catch (RuntimeException e) {
                    terminated = event instanceof AgentEvent.Terminated;
                }
                if (terminated) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 处理 fork 事件；终止时把最终回复摘要回流主对话历史。 */
    private boolean handleFork(AgentEvent event) {
        if (event instanceof AgentEvent.ThinkingDelta delta) {
            view.appendThinking(delta.text());
        } else if (event instanceof AgentEvent.TextDelta delta) {
            view.appendAnswer(delta.text());
            textStreamed = true;
        } else if (event instanceof AgentEvent.ToolStarted started) {
            view.showToolEvent("tool_start", started.toolName(), started.callId(), started.safeSummary());
        } else if (event instanceof AgentEvent.ToolFinished finished) {
            ToolResult result = finished.result();
            String type = switch (result.status()) {
                case SUCCESS, TRUNCATED -> "tool_completed";
                case RESTRICTED -> "tool_blocked";
                default -> "tool_failed";
            };
            view.showToolEvent(type, finished.toolName(), finished.callId(),
                    ToolSummary.ofResult(new ToolResultContent(result, finished.callId(), finished.toolName())));
        } else if (event instanceof AgentEvent.PermissionRequested permission) {
            view.showPermissionRequest(new HitlRequest(permission.callId(), permission.toolName(),
                    permission.safeSummary(), permission.reason()));
        } else if (event instanceof AgentEvent.FinalReply reply) {
            forkFinalText = reply.text();
            if (!textStreamed) {
                view.appendAnswer(reply.text());
            }
            view.finishAssistantMessage();
        } else if (event instanceof AgentEvent.Error error) {
            view.showError(error.safeMessage());
        } else if (event instanceof AgentEvent.Terminated terminated) {
            if (terminated.reason() == AgentTerminationReason.COMPLETED
                    || terminated.reason() == AgentTerminationReason.ROUND_LIMIT) {
                if (forkFinalText != null && !forkFinalText.isBlank()) {
                    conversation.commit(new ConversationTurn(
                            List.of(new ConversationContent.AssistantText(forkFinalText)), null, null));
                }
            }
            finishBusy();
            return true;
        }
        return false;
    }

    /** 手动触发记忆提取（仅空闲时执行）。 */
    public boolean memoryExtract() {
        if (busy.get() || memoryExtractor == null || !memoryConfig.enabled()) return false;
        return triggerMemoryExtraction();
    }

    public void setMemoryStore(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    public MemoryStore memoryStore() {
        return memoryStore;
    }

    public void setWorkingDirectory(java.nio.file.Path directory) {
        workingDirectory = directory.toAbsolutePath().normalize();
    }

    public java.nio.file.Path workingDirectory() {
        return workingDirectory;
    }

    public synchronized boolean hasActiveMemoryExtraction() {
        return memoryExtraction != null && !memoryExtraction.isDone();
    }

    /** 触发异步记忆提取（不阻塞主对话）。 */
    private synchronized boolean triggerMemoryExtraction() {
        MemoryStore store = memoryStore;
        if (memoryExtractor == null || store == null
                || memoryExtraction != null && !memoryExtraction.isDone()) return false;
        List<ConversationTurn> recentTurns = conversation.snapshot();
        if (recentTurns.isEmpty()) return false;
        memoryExtraction = memoryExtractor.extractAsync(store.readMerged(), recentTurns, store);
        return true;
    }

    private void consume(AgentRun run) {
        try {
            while (true) {
                AgentEvent event = run.takeEvent();
                boolean terminated;
                try {
                    terminated = handle(event);
                } catch (RuntimeException e) {
                    // 单个事件处理失败不能让消费者线程静默死亡，
                    // 否则后续 HITL 确认框与工具事件将永不渲染。
                    terminated = event instanceof AgentEvent.Terminated;
                }
                if (terminated) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 处理单个事件。返回 true 表示收到终止事件。 */
    private boolean handle(AgentEvent event) {
        if (event instanceof AgentEvent.UserMessage user) {
            textStreamed = false;
            view.showUserMessage(user.text());
            view.beginAssistantMessage();
        } else if (event instanceof AgentEvent.ThinkingDelta delta) {
            view.appendThinking(delta.text());
        } else if (event instanceof AgentEvent.TextDelta delta) {
            view.appendAnswer(delta.text());
            textStreamed = true;
        } else if (event instanceof AgentEvent.ToolStarted started) {
            view.showToolEvent("tool_start", started.toolName(), started.callId(), started.safeSummary());
        } else if (event instanceof AgentEvent.ToolFinished finished) {
            ToolResult result = finished.result();
            String type = switch (result.status()) {
                case SUCCESS, TRUNCATED -> "tool_completed";
                case RESTRICTED -> "tool_blocked";
                default -> "tool_failed";
            };
            view.showToolEvent(type, finished.toolName(), finished.callId(),
                    ToolSummary.ofResult(new ToolResultContent(result, finished.callId(), finished.toolName())));
        } else if (event instanceof AgentEvent.PermissionRequested permission) {
            view.showPermissionRequest(new HitlRequest(permission.callId(), permission.toolName(),
                    permission.safeSummary(), permission.reason()));
        } else if (event instanceof AgentEvent.RoundUsageCommitted u) {
            view.showUsage(u.usage(), cumulativeUsage());
            // 每轮提交后增量存档
            if (sessionManager != null) {
                sessionManager.savePending();
                sessionManager.bumpRound();
                // 达到间隔时触发异步记忆提取
                if (memoryExtractor != null && memoryConfig != null && memoryConfig.enabled()) {
                    if (sessionManager.roundCount() % memoryConfig.extractIntervalRounds() == 0) {
                        triggerMemoryExtraction();
                    }
                }
            }
        } else if (event instanceof AgentEvent.FinalReply reply) {
            if (!textStreamed) {
                view.appendAnswer(reply.text());
            }
            view.finishAssistantMessage();
        } else if (event instanceof AgentEvent.Error error) {
            view.showError(error.safeMessage());
        } else if (event instanceof AgentEvent.Terminated terminated) {
            if (terminated.reason() == AgentTerminationReason.CANCELLED) {
                view.markAssistantAborted();
            }
            if (terminated.reason() == AgentTerminationReason.COMPLETED
                    || terminated.reason() == AgentTerminationReason.ROUND_LIMIT) {
                Usage cumulative = cumulativeUsage();
                if (cumulative != null) {
                    view.showUsage(cumulative, cumulative);
                }
            }
            finishBusy();
            return true;
        }
        return false;
    }

    /** 从已提交轮次累计用量。 */
    private Usage cumulativeUsage() {
        List<ConversationTurn> turns = conversation.snapshot();
        Integer input = 0;
        Integer output = 0;
        Integer thinking = 0;
        Integer cacheCreate = 0;
        Integer cacheRead = 0;
        Integer total = 0;
        boolean known = false;
        for (ConversationTurn turn : turns) {
            Usage usage = turn.usage();
            if (usage == null) continue;
            if (usage.inputTokens() != null) { input += usage.inputTokens(); known = true; }
            if (usage.outputTokens() != null) { output += usage.outputTokens(); known = true; }
            if (usage.thinkingTokens() != null) { thinking += usage.thinkingTokens(); known = true; }
            if (usage.cacheCreationTokens() != null) { cacheCreate += usage.cacheCreationTokens(); known = true; }
            if (usage.cacheReadTokens() != null) { cacheRead += usage.cacheReadTokens(); known = true; }
            if (usage.totalTokens() != null) { total += usage.totalTokens(); known = true; }
        }
        if (!known) return null;
        return new Usage(input, output, thinking, cacheCreate, cacheRead, total, null, null, null, null);
    }

    private void finishBusy() {
        activeRun = null;
        busy.set(false);
        view.setBusy(false);
    }
}
