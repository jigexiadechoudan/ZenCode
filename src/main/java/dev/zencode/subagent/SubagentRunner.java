package dev.zencode.subagent;

import dev.zencode.agent.Agent;
import dev.zencode.agent.AgentEvent;
import dev.zencode.agent.AgentRun;
import dev.zencode.agent.AgentRunOptions;
import dev.zencode.agent.AgentTerminationReason;
import dev.zencode.agent.execution.ToolInterceptorChain;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.Usage;
import dev.zencode.config.Protocol;
import dev.zencode.config.ProviderConfig;
import dev.zencode.hook.HookEngine;
import dev.zencode.hook.HookToolInterceptor;
import dev.zencode.permission.DangerCommandDetector;
import dev.zencode.permission.PathSandbox;
import dev.zencode.permission.PermissionChecker;
import dev.zencode.permission.PermissionInterceptor;
import dev.zencode.permission.PermissionMode;
import dev.zencode.permission.rule.RuleEngine;
import dev.zencode.prompt.RequestAssembler;
import dev.zencode.prompt.SystemPrompt;
import dev.zencode.prompt.SystemPromptBuilder;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderFactory;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.WorkspacePathResolver;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 子 Agent 执行编排：构造隔离对话、过滤工具集、可选独立 Provider、注入角色指令，
 * 复用 {@link Agent} 主循环「跑到底」，订阅事件流收集结果。
 */
public final class SubagentRunner {

    /** 全局禁止工具（委派工具自身，防 A→B→C 嵌套）。 */
    public static final Set<String> GLOBAL_DENY = Set.of("agent");

    /** Fork 路径首条用户消息的强硬约束。 */
    static final String FORK_CONSTRAINT = """
            [子 Agent 约束]
            你是主 Agent 派生的子 Agent，遵守以下强硬约束：
            1. 禁止再次 fork 或委派其他子 Agent；
            2. 不要主动发起对话，直接执行任务；
            3. 不要请求用户确认，直接用工具干活；
            4. 完成后输出最终报告，控制在 200 字以内，并按结构化字段输出（结论 / 证据 / 建议）。
            """;

    /** 定义式：空白对话 + 固定角色，注入角色指令/模型/轮数/工具过滤。 */
    public SubagentResult runDefinition(AgentDefinition def, String task, SubagentContext ctx) {
        return runDefinition(def, task, ctx, ignored -> {});
    }

    public SubagentResult runDefinition(AgentDefinition def, String task, SubagentContext ctx,
                                        Consumer<AgentRun> onRun) {
        return runDefinition(def, task, ctx, onRun, false);
    }

    public SubagentResult runDefinition(AgentDefinition def, String task, SubagentContext ctx,
                                        Consumer<AgentRun> onRun, boolean forceReadOnly) {
        Conversation conversation = new Conversation();
        ToolFilter filter = new ToolFilter(GLOBAL_DENY, Set.copyOf(def.tools()),
                Set.copyOf(def.disallowedTools()), Set.of());

        Path workDir = ctx.workingDirectory();
        String worktreeName = null;
        String effectiveTask = task;
        // 隔离模式：自动创建独立 worktree 目录，注入路径翻译上下文
        if (!forceReadOnly && def.isolation() && ctx.worktreeManager() != null) {
            var wt = ctx.worktreeManager().createIsolated(def.name());
            if (wt.success() && wt.info() != null) {
                workDir = wt.info().path();
                worktreeName = wt.info().name();
                effectiveTask = "当前位于隔离工作目录: " + workDir
                        + "\n（所有相对路径均相对于该目录，完成后结果由系统处理）\n\n任务：\n" + task;
            }
        }

        SubagentResult result = runInternal(def, conversation, effectiveTask, ctx, filter,
                workDir, onRun, forceReadOnly);

        if (worktreeName != null) {
            ctx.worktreeManager().autoCleanup(worktreeName);
        }
        return result;
    }

    /** Fork：继承父完整历史 + 首条强硬约束，无角色层工具限制（仅全局禁止）。 */
    public SubagentResult runFork(Conversation parent, String task, SubagentContext ctx) {
        return runFork(parent, task, ctx, ignored -> {});
    }

    public SubagentResult runFork(Conversation parent, String task, SubagentContext ctx,
                                  Consumer<AgentRun> onRun) {
        Conversation conversation = Conversation.forkFrom(parent);
        ToolFilter filter = new ToolFilter(GLOBAL_DENY, Set.of(), Set.of(), Set.of());
        String message = FORK_CONSTRAINT + "\n\n任务：\n" + task;
        return runInternal(null, conversation, message, ctx, filter, ctx.workingDirectory(), onRun, false);
    }

    private SubagentResult runInternal(AgentDefinition def, Conversation conversation,
                                       String userMessage, SubagentContext ctx, ToolFilter filter,
                                       Path workDir, Consumer<AgentRun> onRun, boolean forceReadOnly) {
        String taskId = UUID.randomUUID().toString();
        String label = def != null ? def.name() : "fork";
        TraceNode node = ctx.traceRegistry() != null ? ctx.traceRegistry().start(null, label) : null;

        ToolRegistry filtered = filterRegistry(ctx.sourceRegistry().forWorkspace(workDir), filter);
        ChatProvider provider = resolveProvider(def, ctx);
        ProviderConfig base = ctx.baseConfig();
        String modelName = def != null && def.model().isPresent() ? def.model().get()
                : base != null ? base.model() : "未知";
        HookEngine hookEngine = forceReadOnly ? new HookEngine()
                : ctx.hookEngine() != null ? ctx.hookEngine() : new HookEngine();

        // 子 Agent 权限边界：空规则引擎（会话级规则隔离）+ hitlBroker=null（非交互，不请求确认）
        RuleEngine ruleEngine = new RuleEngine(null, null);
        PermissionMode mode = forceReadOnly ? PermissionMode.READ_ONLY
                : def != null ? def.permissionMode() : PermissionMode.DEFAULT;
        PermissionChecker checker = new PermissionChecker(
                new DangerCommandDetector(),
                new PathSandbox(new WorkspacePathResolver(workDir)),
                ruleEngine, mode, filtered);
        ToolInterceptorChain chain = forceReadOnly
                ? new ToolInterceptorChain(List.of(
                        new dev.zencode.agent.execution.PlanOnlyInterceptor(filtered),
                        new PermissionInterceptor(checker, ruleEngine, null, hookEngine)))
                : new ToolInterceptorChain(List.of(
                        new PermissionInterceptor(checker, ruleEngine, null, hookEngine),
                        new HookToolInterceptor(hookEngine)));

        // 角色指令注入 system prompt
        String basePrompt = SystemPromptBuilder.defaults().build().text();
        String promptText = def != null && !def.body().isBlank()
                ? basePrompt + "\n\n--- 子 Agent 角色\n" + def.body()
                : basePrompt;
        SystemPrompt systemPrompt = new SystemPrompt(promptText);

        Protocol protocol = toProtocol(provider.source());
        List<Map<String, Object>> schemas = filtered.schemas(protocol);
        RequestAssembler assembler = new RequestAssembler(
                workDir, modelName, systemPrompt, schemas);
        assembler.setHookEngine(hookEngine);

        int maxRounds = def != null ? def.maxRounds() : AgentDefinition.defaultMaxRounds();
        Agent agent = new Agent(provider, filtered, workDir, chain, modelName,
                null, assembler, hookEngine);
        agent.setExecutionJournal(new dev.zencode.agent.execution.ToolExecutionJournal(workDir));
        AgentRunOptions options = new AgentRunOptions(false, maxRounds,
                Duration.ofSeconds(120), 64);
        AgentRun run = agent.run(conversation, userMessage, options);
        onRun.accept(run);

        String output = "";
        Usage usage = Usage.unknown();
        TaskStatus status = TaskStatus.FAILED;
        try {
            while (true) {
                AgentEvent event = run.takeEvent();
                if (event instanceof AgentEvent.FinalReply reply) {
                    output = reply.text();
                    status = TaskStatus.COMPLETED;
                } else if (event instanceof AgentEvent.RoundUsageCommitted ruc) {
                    usage = accumulate(usage, ruc.usage());
                } else if (event instanceof AgentEvent.Terminated term) {
                    status = toStatus(term.reason());
                    break;
                }
            }
        } catch (InterruptedException e) {
            run.cancel();
            Thread.currentThread().interrupt();
            status = TaskStatus.CANCELLED;
            output = "执行被中断";
        }

        if (node != null) {
            ctx.traceRegistry().finish(node.id(), status, usage);
        }
        return new SubagentResult(taskId, status, output, usage);
    }

    private static ToolRegistry filterRegistry(ToolRegistry source, ToolFilter filter) {
        Set<String> allowed = filter.resolve(new LinkedHashSet<>(source.toolNames()));
        ToolRegistry result = new ToolRegistry();
        for (String name : source.enabledToolNames()) {
            if (allowed.contains(name)) {
                source.find(name).ifPresent(result::register);
            }
        }
        return result;
    }

    private static ChatProvider resolveProvider(AgentDefinition def, SubagentContext ctx) {
        if (def != null && def.model().isPresent() && ctx.baseConfig() != null) {
            ProviderConfig base = ctx.baseConfig();
            ProviderConfig sub = new ProviderConfig(base.name(), base.protocol(), def.model().get(),
                    base.baseUrl(), base.apiKey(), base.thinking());
            return ProviderFactory.create(sub);
        }
        return ctx.parentProvider();
    }

    private static Protocol toProtocol(String source) {
        if ("anthropic".equals(source)) return Protocol.ANTHROPIC;
        if ("openai".equals(source)) return Protocol.OPENAI;
        throw new IllegalArgumentException("未知 Provider 来源: " + source);
    }

    private static TaskStatus toStatus(AgentTerminationReason reason) {
        return switch (reason) {
            case COMPLETED -> TaskStatus.COMPLETED;
            case ROUND_LIMIT -> TaskStatus.ROUND_LIMIT;
            case CANCELLED -> TaskStatus.CANCELLED;
            case ERROR -> TaskStatus.FAILED;
        };
    }

    private static Usage accumulate(Usage a, Usage b) {
        if (a == null) return b == null ? Usage.unknown() : b;
        if (b == null) return a;
        return new Usage(
                add(a.inputTokens(), b.inputTokens()),
                add(a.outputTokens(), b.outputTokens()),
                add(a.thinkingTokens(), b.thinkingTokens()),
                add(a.cacheCreationTokens(), b.cacheCreationTokens()),
                add(a.cacheReadTokens(), b.cacheReadTokens()),
                add(a.totalTokens(), b.totalTokens()),
                null, null, null, null);
    }

    private static Integer add(Integer x, Integer y) {
        if (x == null) return y;
        if (y == null) return x;
        return x + y;
    }
}
