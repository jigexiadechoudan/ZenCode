package dev.zencode.team;

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
import dev.zencode.subagent.AgentDefinition;
import dev.zencode.subagent.AgentLoader;
import dev.zencode.subagent.SubagentContext;
import dev.zencode.subagent.SubagentResult;
import dev.zencode.subagent.TaskStatus;
import dev.zencode.subagent.ToolFilter;
import dev.zencode.subagent.TraceNode;
import dev.zencode.tool.Tool;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.WorkspacePathResolver;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 队员执行编排：复用 {@link Agent} 主循环「跑到底」，注入协作工具（task/send_message）、
 * 轮次间邮箱消息注入（mailSupplier）、transcript 增量落盘；首跑（runMember）与
 * 续写（resumeMember，从磁盘恢复会话）共用同一执行路径。外部进程后端（tmux/iTerm2
 * 中的 headless CLI）复用同一套逻辑。
 */
public final class TeamMemberRunner {

    /** 全局禁止工具（委派工具自身，防嵌套团队）。 */
    public static final Set<String> GLOBAL_DENY = Set.of("agent");

    private final AgentLoader agentLoader;

    /** @param agentLoader 角色定义加载器（可空；空=队员角色恒为通用执行） */
    public TeamMemberRunner(AgentLoader agentLoader) {
        this.agentLoader = agentLoader;
    }

    /** 首跑：空会话 + 协作工具注入 + 邮箱轮询 + transcript 落盘。 */
    public SubagentResult runMember(TeamRuntime rt, TeamMember member, String task,
                                    SubagentContext ctx, List<Tool> extraTools) {
        Conversation conversation = new Conversation();
        return runInternal(rt, member, conversation, task, ctx, extraTools);
    }

    /** 续写：从磁盘 transcript 恢复会话，以消息为新输入继续 run（首轮 seed 已在，不重注入）。 */
    public SubagentResult resumeMember(TeamRuntime rt, TeamMember member, String message,
                                       SubagentContext ctx, List<Tool> extraTools) {
        Conversation conversation = rt.transcript().load(member.agentId());
        return runInternal(rt, member, conversation, message, ctx, extraTools);
    }

    private SubagentResult runInternal(TeamRuntime rt, TeamMember member, Conversation conversation,
                                       String userMessage, SubagentContext ctx, List<Tool> extraTools) {
        String taskId = UUID.randomUUID().toString();
        TraceNode node = ctx.traceRegistry() != null
                ? ctx.traceRegistry().start(null, "member-" + member.name()) : null;

        AgentDefinition def = resolveRole(member);
        ToolFilter filter = new ToolFilter(GLOBAL_DENY,
                def != null ? new LinkedHashSet<>(def.tools()) : new LinkedHashSet<>(),
                def != null ? new LinkedHashSet<>(def.disallowedTools()) : new LinkedHashSet<>(),
                new LinkedHashSet<>());
        Path workDir = Path.of(member.workDir());
        ToolRegistry filtered = filterRegistry(ctx.sourceRegistry().forWorkspace(workDir), filter);
        if (extraTools != null) {
            for (Tool tool : extraTools) {
                filtered.register(tool);
            }
        }

        ChatProvider provider = resolveProvider(def, member, ctx);
        ProviderConfig base = ctx.baseConfig();
        String modelName = member.model() != null && !member.model().isBlank()
                ? member.model() : def != null && def.model().isPresent() ? def.model().get()
                : base != null ? base.model() : "未知";
        HookEngine hookEngine = ctx.hookEngine() != null ? ctx.hookEngine() : new HookEngine();

        // 权限边界：空规则引擎（会话级隔离）；needApproval=true 走审批流拦截器
        RuleEngine ruleEngine = new RuleEngine(null, null);
        PermissionMode mode = def != null ? def.permissionMode() : PermissionMode.DEFAULT;
        PermissionChecker checker = new PermissionChecker(
                new DangerCommandDetector(),
                new PathSandbox(new WorkspacePathResolver(workDir)),
                ruleEngine, mode, filtered);
        var chain = new ToolInterceptorChain(List.of(
                member.needApproval()
                        ? new TeamApprovalInterceptor(checker, rt, member.agentId())
                        : new PermissionInterceptor(checker, ruleEngine, null, hookEngine),
                new HookToolInterceptor(hookEngine)));

        String basePrompt = SystemPromptBuilder.defaults().build().text();
        String promptText = def != null && !def.body().isBlank()
                ? basePrompt + "\n\n--- 队员角色\n" + def.body()
                : basePrompt;
        SystemPrompt systemPrompt = new SystemPrompt(promptText);

        Protocol protocol = toProtocol(provider.source());
        List<Map<String, Object>> schemas = filtered.schemas(protocol);
        RequestAssembler assembler = new RequestAssembler(
                workDir, modelName, systemPrompt, schemas);
        assembler.setHookEngine(hookEngine);
        assembler.setMailSupplier(() -> formatMails(rt.mailbox().readNew(member.agentId())));

        int maxRounds = member.maxRounds() > 0 ? member.maxRounds()
                : (def != null ? def.maxRounds() : AgentDefinition.defaultMaxRounds());
        Agent agent = new Agent(provider, filtered, workDir, chain, modelName,
                null, assembler, hookEngine);
        agent.setExecutionJournal(new dev.zencode.agent.execution.ToolExecutionJournal(
                workDir, () -> member.agentId()));
        AgentRunOptions options = new AgentRunOptions(false, maxRounds,
                Duration.ofSeconds(120), 64);
        AgentRun run = agent.run(conversation, userMessage, options);
        rt.putRun(member.agentId(), run);

        String output = "";
        Usage usage = Usage.unknown();
        TaskStatus status = TaskStatus.FAILED;
        try {
            while (true) {
                AgentEvent event = run.takeEvent();
                if (event instanceof AgentEvent.FinalReply reply) {
                    output = reply.text();
                    status = TaskStatus.COMPLETED;
                } else if (event instanceof AgentEvent.RoundUsageCommitted) {
                    rt.transcript().append(member.agentId(), conversation.snapshot());
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
        } finally {
            rt.removeRun(member.agentId());
        }

        if (node != null) {
            ctx.traceRegistry().finish(node.id(), status, usage);
        }
        return new SubagentResult(taskId, status, output, usage);
    }

    /** 解析角色定义；未知名角色或空角色返回 null（通用执行，仅全局禁止）。 */
    private AgentDefinition resolveRole(TeamMember member) {
        if (agentLoader == null || member.role() == null || member.role().isBlank()) {
            return null;
        }
        Optional<AgentDefinition> def = agentLoader.find(member.role());
        if (def.isEmpty()) {
            throw new TeamException("未知队员角色: " + member.role());
        }
        return def.get();
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

    private static ChatProvider resolveProvider(AgentDefinition def, TeamMember member,
                                                SubagentContext ctx) {
        String model = member.model() != null && !member.model().isBlank()
                ? member.model() : (def != null && def.model().isPresent() ? def.model().get() : null);
        if (model != null && ctx.baseConfig() != null) {
            ProviderConfig base = ctx.baseConfig();
            ProviderConfig sub = new ProviderConfig(base.name(), base.protocol(), model,
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

    /** 新邮件格式化为提醒文本（消费式：readNew 已推进已读 marker）。 */
    private static List<String> formatMails(List<TeamMessage> mails) {
        List<String> out = new ArrayList<>();
        for (TeamMessage mail : mails) {
            StringBuilder sb = new StringBuilder();
            sb.append("[邮件 ").append(mail.type()).append("] ");
            sb.append(mail.from()).append(" → ").append(mail.to()).append(": ");
            if (!mail.subject().isBlank()) sb.append(mail.subject());
            if (!mail.body().isBlank()) sb.append(" ").append(mail.body());
            if (!mail.protocol().isEmpty()) sb.append(" 协议=").append(mail.protocol());
            out.add(sb.toString());
        }
        return out;
    }
}
