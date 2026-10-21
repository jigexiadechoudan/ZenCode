package dev.zencode;

import dev.zencode.app.ConversationController;
import dev.zencode.agent.Agent;
import dev.zencode.agent.AgentRunOptions;
import dev.zencode.agent.execution.ToolInterceptorChain;
import dev.zencode.chat.Conversation;
import dev.zencode.cli.CliOptions;
import dev.zencode.command.CommandMode;
import dev.zencode.command.CommandRegistry;
import dev.zencode.command.CommandRouter;
import dev.zencode.command.CommandSpec;
import dev.zencode.command.builtin.BuiltInCommands;
import dev.zencode.config.ConfigException;
import dev.zencode.config.ConfigLoader;
import dev.zencode.config.ContextConfig;
import dev.zencode.config.Protocol;
import dev.zencode.context.ContextCompressor;
import dev.zencode.context.SummaryGenerator;
import dev.zencode.context.ToolResultSpooler;
import dev.zencode.hook.HookConfigException;
import dev.zencode.hook.HookContext;
import dev.zencode.hook.HookEngine;
import dev.zencode.hook.HookEvent;
import dev.zencode.hook.HookLoader;
import dev.zencode.hook.HookToolInterceptor;
import dev.zencode.mcp.McpManager;
import dev.zencode.memory.InstructionLoader;
import dev.zencode.memory.MemoryConfig;
import dev.zencode.memory.MemoryExtractor;
import dev.zencode.memory.MemoryStore;
import dev.zencode.memory.SessionManager;
import dev.zencode.permission.DangerCommandDetector;
import dev.zencode.permission.PathSandbox;
import dev.zencode.permission.PermissionChecker;
import dev.zencode.permission.PermissionInterceptor;
import dev.zencode.permission.PermissionMode;
import dev.zencode.permission.hitl.HitlBroker;
import dev.zencode.permission.rule.RuleEngine;
import dev.zencode.prompt.RequestAssembler;
import dev.zencode.prompt.SystemPromptBuilder;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderFactory;
import dev.zencode.provider.SwitchableProvider;
import dev.zencode.session.SessionStore;
import dev.zencode.skill.ActiveSkills;
import dev.zencode.skill.ActivateSkillTool;
import dev.zencode.skill.LoadSkillTool;
import dev.zencode.skill.SkillConfig;
import dev.zencode.skill.SkillDefinition;
import dev.zencode.skill.SkillLoader;
import dev.zencode.skill.SkillManager;
import dev.zencode.skill.SkillRegistry;
import dev.zencode.skill.SkillWhitelistInterceptor;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.WorkspacePathResolver;
import dev.zencode.web.BrowserConversationView;
import dev.zencode.web.BrowserWorkbench;
import dev.zencode.web.ModelConfigStore;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public final class ZenCode {
    private static final Duration HITL_TIMEOUT = Duration.of(2, ChronoUnit.MINUTES);
    private ZenCode() {}

    /**
     * 外部队员模式（无头）：加载团队与队员 → 首跑 spawn-task（或邮箱首条任务）→
     * 轮询邮箱（间隔 2s，最多空闲 300s）→ 有新消息从磁盘 transcript 恢复续写 →
     * 团队目录被删（TeamDelete）或进程被杀时退出。不启动 TUI、无 HITL。
     * 与 in-process 队员共用同一 {@link dev.zencode.team.TeamMemberRunner} 逻辑。
     */
    private static void runMemberMode(CliOptions options, ConfigLoader.LoadedConfig loaded) throws Exception {
        java.nio.file.Path workDir = java.nio.file.Path.of(System.getProperty("user.dir"));
        dev.zencode.provider.ChatProvider provider = dev.zencode.provider.ProviderFactory.create(loaded.selectedProvider());
        dev.zencode.team.TeamStore store = new dev.zencode.team.TeamStore(workDir);
        var loadedTeam = store.load(options.team());
        if (loadedTeam.isEmpty()) {
            System.err.println("团队不存在: " + options.team());
            System.exit(2);
        }
        dev.zencode.team.Team team = loadedTeam.get().team();
        dev.zencode.team.TeamRuntime rt = new dev.zencode.team.TeamRuntime(team, store.teamDir(team.name()));
        for (dev.zencode.team.TeamMember m : loadedTeam.get().roster()) {
            rt.putMember(m);
        }
        dev.zencode.team.TeamMember member = rt.memberByAgentId(options.member())
                .orElseThrow(() -> new dev.zencode.team.TeamException("队员不存在: " + options.member()));

        dev.zencode.tool.ToolRegistry registry = dev.zencode.tool.ToolRegistry.createDefault(workDir);
        dev.zencode.subagent.AgentLoader agentLoader = new dev.zencode.subagent.AgentLoader(
                null, null,
                java.nio.file.Path.of(System.getProperty("user.home"))
                        .resolve(".zencode").resolve("agents"),
                workDir.resolve(".zencode").resolve("agents"));
        dev.zencode.subagent.SubagentContext ctx = new dev.zencode.subagent.SubagentContext(
                provider, loaded.selectedProvider(), workDir, null,
                new dev.zencode.subagent.TraceRegistry(), registry, null);
        dev.zencode.team.TeamMemberRunner runner = new dev.zencode.team.TeamMemberRunner(agentLoader);
        java.util.List<dev.zencode.tool.Tool> extra = java.util.List.of(
                new dev.zencode.team.TeamTaskTool(rt),
                new dev.zencode.team.SendMessageTool(rt, null, member.agentId()));

        // 首跑：spawn-task 或空任务
        String task = options.spawnTask() == null ? "" : options.spawnTask();
        dev.zencode.subagent.SubagentResult result = runner.runMember(rt, member, task, ctx, extra);
        if (!finishIdle(store, rt, member, result)) return;

        // 邮箱轮询：新消息 → 续写；团队目录消失（TeamDelete）或超时 → 退出
        long idleDeadline = System.currentTimeMillis() + 300_000L;
        while (System.currentTimeMillis() < idleDeadline) {
            if (!java.nio.file.Files.exists(rt.teamDir())) {
                break;
            }
            java.util.List<dev.zencode.team.TeamMessage> mails = rt.mailbox().readNew(member.agentId());
            boolean resumed = false;
            for (dev.zencode.team.TeamMessage mail : mails) {
                if ((mail.type() == dev.zencode.team.MessageType.TEXT
                        || mail.type() == dev.zencode.team.MessageType.BROADCAST)
                        && !mail.body().isBlank()) {
                    result = runner.resumeMember(rt, member, mail.body(), ctx, extra);
                    if (!finishIdle(store, rt, member, result)) return;
                    resumed = true;
                }
            }
            if (resumed) {
                idleDeadline = System.currentTimeMillis() + 300_000L;
            }
            Thread.sleep(2000);
        }
    }

    /** 外部队员完成后：状态 IDLE 落盘 + 发 lifecycle 消息给 Lead。 */
    static boolean finishIdle(dev.zencode.team.TeamStore store,
                              dev.zencode.team.TeamRuntime rt,
                              dev.zencode.team.TeamMember member,
                              dev.zencode.subagent.SubagentResult result) {
        var idle = member.withStatus(dev.zencode.team.MemberStatus.IDLE);
        if (!store.updateExistingMember(rt.team(), idle)) {
            store.load(rt.team().name()).ifPresent(stored -> stored.roster().forEach(rt::putMember));
            return false;
        }
        rt.putMember(idle);
        String output = result == null || result.output() == null ? "无结果" : result.output();
        String summary = output.length() > 120 ? output.substring(0, 120) + "…" : output;
        rt.mailbox().send(member.agentId(), "lead", dev.zencode.team.MessageType.LIFECYCLE,
                "队员空闲", summary, java.util.Map.of("event", "IDLE"));
        return true;
    }

    public static void main(String[] args) {
        try {
            CliOptions options = CliOptions.parse(args);
            if (options.team() != null && options.member() != null) {
                throw new ConfigException("会话密钥模式不支持外部团队进程，请使用 INPROCESS 队员。");
            }
            ModelConfigStore settings = new ModelConfigStore(options, null);
            settings.clearSavedKeys();
            System.out.println("请填写本次运行使用的 API Key。密钥仅在内存中使用，退出后失效。");
            try (BrowserWorkbench app = new BrowserWorkbench(settings, System.getProperty("user.dir"),
                    (config, context, provider) -> createRuntime(options, config, context, provider))) {
                Thread shutdown = new Thread(app::close, "zencode-browser-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdown);
                try { app.run(); }
                finally {
                    app.close();
                    try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                    catch (IllegalStateException ignored) { /* JVM 已在退出 */ }
                }
            }
        } catch (Exception e) {
            // Exception messages can contain configuration URLs/credentials. Only emit safe categories.
            System.err.println("程序启动失败 category=" + e.getClass().getSimpleName()
                    + "；请检查 Java、启动参数、本地配置和工作目录权限。");
            System.exit(1);
        }
    }

    /** Build once, including after first-time setup; ordinary model changes never recreate conversation state. */
    private static BrowserWorkbench.RuntimeSession createRuntime(CliOptions options, ConfigLoader.LoadedConfig loaded,
            BrowserWorkbench.RuntimeContext context, SwitchableProvider provider)
            throws Exception {
        java.util.Deque<Runnable> resources = new java.util.ArrayDeque<>();
        try {
            java.nio.file.Path workDir = context.workingDirectory();
            var switching = new java.util.concurrent.atomic.AtomicBoolean();

            // Worktree 系统装配：git 仓库下启用，非 git 降级；--resume 恢复 worktree 目录
            dev.zencode.worktree.WorktreeManager worktreeManager = null;
            if (dev.zencode.worktree.GitCommand.isGitRepository(workDir)) {
                dev.zencode.worktree.WorktreeSessionStore worktreeStore =
                        new dev.zencode.worktree.WorktreeSessionStore(workDir);
                worktreeManager = new dev.zencode.worktree.WorktreeManager(
                        workDir, loaded.worktree(), worktreeStore, () -> {});
                if (context.initial() && options.resume() && worktreeManager.currentName() != null) {
                    workDir = worktreeManager.currentDirectory();
                } else if (worktreeManager.currentName() != null) {
                    worktreeManager.exit();
                }
            }
            WorkspacePathResolver resolver = new WorkspacePathResolver(workDir);
            ToolRegistry toolRegistry = ToolRegistry.createDefault(resolver);

            // MCP 工具接入：连接配置的 Server、发现并注册其工具（失败已隔离，不阻塞启动）
            McpManager mcpManager = new McpManager(toolRegistry);
            resources.push(mcpManager::close);
            mcpManager.connectAll(loaded.appConfig().mcpServers());

            // Skill 技能系统装配：加载内置/用户/项目级 Skill，注册系统工具
            SkillRegistry skillRegistry = new SkillRegistry();
            ActiveSkills activeSkills = new ActiveSkills();
            toolRegistry.register(new LoadSkillTool(skillRegistry, activeSkills, toolRegistry));
            toolRegistry.register(new ActivateSkillTool(skillRegistry, activeSkills));
            SkillLoader skillLoader = new SkillLoader(workDir,
                    java.nio.file.Path.of(System.getProperty("user.home")), SkillConfig.defaults());
            Map<String, SkillDefinition> loadedSkills = skillLoader.loadAll(toolRegistry);
            skillRegistry.replaceAll(loadedSkills);

            // Hook 系统装配：加载用户级 + 项目级 hooks.yaml（用户规则在前）
            HookEngine hookEngine = new HookEngine();
            hookEngine.setHooks(new HookLoader().load(
                    java.nio.file.Path.of(System.getProperty("user.home"))
                            .resolve(".zencode").resolve("hooks.yaml"),
                    workDir.resolve(".zencode").resolve("hooks.yaml")));

            // 权限系统装配
            PermissionMode permissionMode = loaded.appConfig().permissionMode();
            RuleEngine ruleEngine = new RuleEngine(
                    workDir.resolve(".zencode").resolve("rules.yaml"),
                    java.nio.file.Path.of(System.getProperty("user.home"))
                            .resolve(".zencode").resolve("rules.yaml"));
            HitlBroker hitlBroker = new HitlBroker(HITL_TIMEOUT.toNanos());
            PermissionChecker permissionChecker = new PermissionChecker(
                    new DangerCommandDetector(), new PathSandbox(resolver), ruleEngine,
                    permissionMode, toolRegistry);
            PermissionInterceptor permissionInterceptor = new PermissionInterceptor(
                    permissionChecker, ruleEngine, hitlBroker, hookEngine);

            // 跨会话记忆系统装配
            Conversation conversation = new Conversation();
            dev.zencode.memory.MemoryConfig memoryCfg = loaded.appConfig().memory();
            MemoryStore memoryStore = null;
            InstructionLoader instructionLoader = null;
            SessionStore sessionStore = new SessionStore(context.storageRoot());
            SessionManager sessionManager = new SessionManager(sessionStore, memoryCfg, null, conversation);
            sessionManager.setProjectId(context.projectId());
            MemoryExtractor memoryExtractor = null;
            if (memoryCfg.enabled()) {
                memoryStore = new MemoryStore(workDir,
                        java.nio.file.Path.of(System.getProperty("user.home")), memoryCfg);
                instructionLoader = new InstructionLoader(workDir,
                        java.nio.file.Path.of(System.getProperty("user.home")), memoryCfg);
                memoryExtractor = new MemoryExtractor(provider, Duration.ofSeconds(30));
            }

            // 命令装配：内置命令 + Skill 短命令 + 管理命令
            CommandRegistry commandRegistry = BuiltInCommands.build();
            SkillManager skillManager = new SkillManager(skillLoader, skillRegistry, activeSkills,
                    commandRegistry, toolRegistry);
            skillManager.registerSkillCommands();
            CommandRouter router = new CommandRouter(commandRegistry, hookEngine);

            ConversationController[] holder = new ConversationController[1];
            BrowserConversationView view = new BrowserConversationView(() -> holder[0]);
            // 上下文管理装配：两层压缩（大结果存盘 + 逼近窗口全量摘要）
            ContextConfig contextConfig = loaded.appConfig().context();
            ContextCompressor contextCompressor = new ContextCompressor(
                    new ToolResultSpooler(workDir, contextConfig),
                    new SummaryGenerator(provider, Duration.ofSeconds(120)),
                    contextConfig);
            contextCompressor.setHookEngine(hookEngine);
            if (sessionManager != null) sessionManager.setCompressor(contextCompressor);

            // 创建带记忆通道与 Skill 通道的自定义 RequestAssembler
            Protocol protocol = "anthropic".equals(loaded.selectedProvider().protocol().name().toLowerCase())
                    ? Protocol.ANTHROPIC : Protocol.OPENAI;
            java.util.List<java.util.Map<String, Object>> schemas = toolRegistry.schemas(protocol);
            RequestAssembler assembler = new RequestAssembler(workDir,
                    loaded.selectedProvider().model(),
                    SystemPromptBuilder.defaults().build(), schemas,
                    instructionLoader, memoryStore,
                    skillRegistry, toolRegistry, protocol, activeSkills);
            assembler.setHookEngine(hookEngine);
            assembler.refreshForNewDirectory(workDir, instructionLoader, memoryStore);

            // 子 Agent 系统装配：链路追踪 + 后台任务 + 委派工具 + 斜杠命令
            dev.zencode.subagent.TraceRegistry traceRegistry = new dev.zencode.subagent.TraceRegistry();
            java.util.List<String> taskNotifications = new java.util.concurrent.CopyOnWriteArrayList<>();
            dev.zencode.subagent.TaskManager taskManager = new dev.zencode.subagent.TaskManager(
                    state -> taskNotifications.add("[后台任务 " + state.status() + "] "
                            + state.description() + " (id=" + state.id() + ")"),
                    Duration.ofSeconds(loaded.appConfig().agents().backgroundTimeoutSeconds()));
            resources.push(() -> taskManager.list().forEach(task -> taskManager.cancel(task.id())));
            assembler.setTaskNotificationSupplier(() -> {
                if (taskNotifications.isEmpty()) return java.util.List.of();
                java.util.List<String> copy = java.util.List.copyOf(taskNotifications);
                taskNotifications.clear();
                return copy;
            });

            dev.zencode.subagent.AgentLoader agentLoader = new dev.zencode.subagent.AgentLoader(
                    null, null,
                    java.nio.file.Path.of(System.getProperty("user.home"))
                            .resolve(".zencode").resolve("agents"),
                    workDir.resolve(".zencode").resolve("agents"));
            if (loaded.appConfig().agents().reviewEnabled()) {
                agentLoader.enable("review");
            }
            dev.zencode.subagent.SubagentContext subagentContext = new dev.zencode.subagent.SubagentContext(
                    provider, loaded.selectedProvider(), workDir, hookEngine, traceRegistry, toolRegistry, worktreeManager);
            toolRegistry.register(new dev.zencode.subagent.SubagentTool(agentLoader,
                    new dev.zencode.subagent.SubagentRunner(), taskManager, subagentContext, conversation));

            commandRegistry.register(new CommandSpec("/tasks", java.util.List.of(),
                    "列出后台任务", "/tasks", "", CommandMode.LOCAL, false,
                    new dev.zencode.command.builtin.TasksCommand(taskManager)));
            commandRegistry.register(new CommandSpec("/task", java.util.List.of(),
                    "查看或取消后台任务", "/task info <id> | cancel <id>",
                    "info <id> / cancel <id>", CommandMode.LOCAL, false,
                    new dev.zencode.command.builtin.TaskCommand(taskManager)));
            // 团队系统装配：TeamManager + 顶层工具 + /team 命令；队员完成通知接入主对话
            dev.zencode.team.TeamManager teamManager = new dev.zencode.team.TeamManager(
                    workDir, loaded.team(), new dev.zencode.team.TeamStore(workDir),
                    worktreeManager, agentLoader, subagentContext,
                    new dev.zencode.team.BackendDetector());
            teamManager.setNotifier(state -> taskNotifications.add("[团队] " + state));
            toolRegistry.register(new dev.zencode.team.TeamCreateTool(teamManager, toolRegistry));
            toolRegistry.register(new dev.zencode.team.TeamSpawnTool(teamManager));
            toolRegistry.register(new dev.zencode.team.TeamTerminateTool(teamManager));
            toolRegistry.register(new dev.zencode.team.TeamMergeTool(teamManager));
            toolRegistry.register(new dev.zencode.team.TeamDeleteTool(teamManager, toolRegistry));
            commandRegistry.register(new CommandSpec("/team", java.util.List.of(),
                    "管理 Agent 团队", "/team list|info|use|coordinator",
                    "list / info <name> / use <name> / coordinator on|off",
                    CommandMode.LOCAL, false,
                    new dev.zencode.team.command.TeamCommand(teamManager,
                            new dev.zencode.team.TeamStore(workDir), toolRegistry, assembler)));
            if (worktreeManager != null) {
                commandRegistry.register(new CommandSpec("/worktree", java.util.List.of(),
                        "管理 Git worktree", "/worktree list|create|enter|exit|remove",
                        "list / create <name> / enter <name> / exit / remove <name> [--discard]",
                        CommandMode.LOCAL, false,
                        new dev.zencode.command.builtin.WorktreeCommand(worktreeManager)));
            }

            Agent agent = new Agent(provider, toolRegistry,
                    workDir,
                    new ToolInterceptorChain(java.util.List.of(
                            new SkillWhitelistInterceptor(activeSkills), permissionInterceptor,
                            new HookToolInterceptor(hookEngine))),
                    loaded.selectedProvider().model(), contextCompressor, assembler, hookEngine);
            SessionManager activeSessionManager = sessionManager;
            dev.zencode.agent.execution.ToolExecutionJournal executionJournal =
                    new dev.zencode.agent.execution.ToolExecutionJournal(workDir,
                            () -> activeSessionManager == null ? "unbound" : activeSessionManager.currentId());
            agent.setExecutionJournal(executionJournal);
            if (sessionManager != null) sessionManager.setExecutionJournal(executionJournal);
            commandRegistry.register(new CommandSpec("/executions", java.util.List.of(),
                    "查询或核实未决副作用工具执行",
                    "/executions list | resolve <executionId> succeeded|failed --verified",
                    "list / resolve <executionId> succeeded|failed --verified",
                    CommandMode.LOCAL, false,
                    new dev.zencode.command.builtin.ExecutionsCommand(executionJournal)));
            ConversationController controller = new ConversationController(conversation, agent, view,
                    AgentRunOptions.defaults(), hitlBroker, permissionChecker, contextCompressor,
                    contextConfig, sessionManager, memoryExtractor, memoryCfg);
            controller.setMemoryStore(memoryStore);
            controller.setWorkingDirectory(workDir);
            if (worktreeManager != null) {
                final dev.zencode.worktree.WorktreeManager wtMgr = worktreeManager;
                wtMgr.setDirectoryChangeAllowed(() -> !controller.isBusy() && !agent.hasActiveRuns()
                        && !taskManager.hasActiveTasks() && !provider.isStreaming()
                        && !controller.hasActiveMemoryExtraction() && !teamManager.hasModelChangeBlockers());
                wtMgr.setCacheInvalidator(() -> {
                    java.nio.file.Path newDir = wtMgr.currentDirectory();
                    java.nio.file.Path homeDir = java.nio.file.Path.of(System.getProperty("user.home"));
                    InstructionLoader newLoader = memoryCfg.enabled()
                            ? new InstructionLoader(newDir, homeDir, memoryCfg) : null;
                    MemoryStore newMemory = memoryCfg.enabled()
                            ? new MemoryStore(newDir, homeDir, memoryCfg) : null;
                    agent.refreshForNewDirectory(newDir);
                    resolver.refreshForNewDirectory(newDir);
                    ruleEngine.refreshProject(newDir.resolve(".zencode/rules.yaml"));
                    contextCompressor.refreshForNewDirectory(newDir);
                    executionJournal.refreshForNewDirectory(newDir);
                    assembler.refreshForNewDirectory(newDir, newLoader, newMemory);
                    controller.setMemoryStore(newMemory);
                    controller.setWorkingDirectory(newDir);
                });
            }
            holder[0] = controller;
            resources.push(controller::close);
            controller.setSkillSystem(skillRegistry, activeSkills);
            controller.setHookEngine(hookEngine);
            if (context.initial()) hookEngine.runHooks(HookEvent.STARTUP, new HookContext(
                    HookEvent.STARTUP.name().toLowerCase(java.util.Locale.ROOT),
                    null, java.util.Map.of(), null, null, null));
            dev.zencode.worktree.WorktreeCleanupTask cleanupTask = null;
            if (worktreeManager != null) {
                cleanupTask = new dev.zencode.worktree.WorktreeCleanupTask(
                        worktreeManager, loaded.worktree().cleanupIntervalSeconds());
                cleanupTask.start();
                resources.push(cleanupTask::stop);
            }
            resources.push(() -> { if (!switching.get()) hookEngine.runHooks(HookEvent.SHUTDOWN, new HookContext(
                    HookEvent.SHUTDOWN.name().toLowerCase(java.util.Locale.ROOT),
                    null, java.util.Map.of(), null, null, null)); });
            java.util.function.BooleanSupplier busy = () -> controller.isBusy() || view.isBusy()
                    || agent.hasActiveRuns() || taskManager.hasActiveTasks() || provider.isStreaming()
                    || controller.hasActiveMemoryExtraction() || teamManager.hasModelChangeBlockers();
            ModelConfigStore.Application applyModel = (config, persist) -> {
                try {
                    teamManager.withModelChangeGuard(() -> {
                        if (controller.isBusy() || view.isBusy() || agent.hasActiveRuns() || taskManager.hasActiveTasks()) {
                            throw new IllegalStateException("仍有任务在运行");
                        }
                        provider.apply(config.selectedProvider(), () -> {
                            persist.run();
                            assembler.refreshModel(config.selectedProvider().model(), config.selectedProvider().protocol());
                        });
                    });
                } catch (dev.zencode.team.TeamManager.ModelChangeBlockedException e) {
                    throw new ModelConfigStore.SettingsException(409, e.getMessage());
                } catch (IllegalStateException e) {
                    throw new ModelConfigStore.SettingsException(409,
                            "仍有聊天、模型请求或后台队员未结束，配置未切换。请等待任务收尾或停止队员后重新应用。");
                }
            };
            var cleaned = new java.util.concurrent.atomic.AtomicBoolean();
            return new BrowserWorkbench.RuntimeSession(view, controller, conversation, router, memoryStore,
                    workDir.toString(), applyModel, busy, () -> {
                        if (cleaned.compareAndSet(false, true)) closeResources(resources);
                    }, () -> {
                        switching.set(true);
                        controller.prepareForSwitchClose();
                        if (cleaned.compareAndSet(false, true)) closeResources(resources);
                    });
        } catch (Exception | Error e) {
            closeResources(resources);
            throw e;
        }
    }

    private static void closeResources(java.util.Deque<Runnable> resources) {
        while (!resources.isEmpty()) {
            try { resources.pop().run(); }
            catch (Exception ignored) { /* Continue closing remaining resources without printing secrets. */ }
        }
    }
}
