package dev.zencode.team;

import dev.zencode.config.TeamConfig;
import dev.zencode.subagent.AgentDefinition;
import dev.zencode.subagent.AgentLoader;
import dev.zencode.subagent.SubagentContext;
import dev.zencode.subagent.SubagentResult;
import dev.zencode.tool.Tool;
import dev.zencode.worktree.GitCommand;
import dev.zencode.worktree.GitCommand.GitResult;
import dev.zencode.worktree.WorktreeInfo;
import dev.zencode.worktree.WorktreeManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 团队生命周期唯一入口：createTeam / spawnMember / terminateMember / mergeTeam /
 * deleteTeam / listTeams / sendMessage（含 IDLE 队员恢复触发）/ coordinator 开关 /
 * 队员完成回调（IDLE + lifecycle 邮件 + 主对话通知）。
 * <p>
 * 当前团队（currentTeamName）由 TeamCreate 或 {@code /team use} 设置，顶层工具
 * 绑定当前团队。in-process 队员经 {@link InProcessBackend} 在虚拟线程运行；
 * tmux/iTerm2 队员经命令构造后端启动外部 CLI 实例。收敛合并（mergeTeam）只处理
 * isolation 队员的独立分支：git merge 冲突时回滚本次合并并交由人工解决。
 * </p>
 */
public final class TeamManager {

    private final Path workDir;
    private final TeamConfig config;
    private final TeamStore store;
    private final WorktreeManager worktreeManager; // 可空
    private final SubagentContext baseContext;
    private final BackendDetector detector;
    private final TeamMemberRunner memberRunner;
    private final InProcessBackend inProcessBackend;
    private final TmuxBackend tmuxBackend;
    private final Iterm2Backend iterm2Backend;
    private final Map<String, TeamRuntime> runtimes = new ConcurrentHashMap<>();
    private final Set<TeamRuntime> retiringRuntimes = ConcurrentHashMap.newKeySet();
    private boolean externalMemberStarted;
    private volatile String currentTeamName;
    private volatile Consumer<String> notifier;

    public TeamManager(Path workDir, TeamConfig config, TeamStore store,
                       WorktreeManager worktreeManager, AgentLoader agentLoader,
                       SubagentContext baseContext, BackendDetector detector) {
        this.workDir = workDir;
        this.config = config;
        this.store = store;
        this.worktreeManager = worktreeManager;
        this.baseContext = baseContext;
        this.detector = detector;
        this.memberRunner = new TeamMemberRunner(agentLoader);
        this.tmuxBackend = new TmuxBackend();
        this.iterm2Backend = new Iterm2Backend();
        this.inProcessBackend = new InProcessBackend(memberRunner, baseContext,
                finish -> onMemberFinished(finish.rt(), finish.member(), finish.result()),
                (rt, member) -> List.of(new TeamTaskTool(rt),
                        new SendMessageTool(rt, this, member.agentId())));
    }

    /** 队员完成/清理等事件通知主对话（可空）。 */
    public void setNotifier(Consumer<String> notifier) {
        this.notifier = notifier;
    }

    /**
     * 与派生/恢复队员使用同一把锁。IDLE 队员仍可恢复，外部队员也不能热更新凭据，
     * 因而必须先显式停止全部已加载队员，再在锁内应用模型配置。
     */
    public synchronized void withModelChangeGuard(Runnable action) {
        Objects.requireNonNull(action, "配置更新操作不能为空");
        // Existing external backends cannot acknowledge physical exit or inherit an in-memory model switch.
        if (externalMemberStarted) {
            throw new ModelChangeBlockedException("本次工作台启动过外部 tmux/iTerm2 队员，无法确认其凭据已释放。请关闭外部队员进程并重启工作台后再更改模型；配置未保存。");
        }
        if (hasModelChangeBlockers()) {
            throw new IllegalStateException("请先停止所有团队队员，再应用模型配置（空闲队员也需要停止）。");
        }
        action.run();
    }

    public static final class ModelChangeBlockedException extends IllegalStateException {
        private ModelChangeBlockedException(String safeMessage) { super(safeMessage); }
    }

    public synchronized boolean hasModelChangeBlockers() {
        retiringRuntimes.removeIf(rt -> !rt.hasActiveWork()
                && rt.members().stream().allMatch(member -> member.status() == MemberStatus.TERMINATED));
        return java.util.stream.Stream.concat(runtimes.values().stream(), retiringRuntimes.stream())
                .anyMatch(rt -> rt.hasActiveWork() || rt.members().stream()
                        .anyMatch(member -> member.status() != MemberStatus.TERMINATED));
    }

    // ---- 团队生命周期 ----

    /** 创建团队（Lead = 主入口会话 "lead"）；重名/非法名抛可读错误。 */
    public synchronized Team createTeam(String name) {
        String flat = TeamStore.sanitize(name);
        if (store.load(flat).isPresent()) {
            throw new TeamException("团队已存在: " + flat);
        }
        Team team = new Team(flat, "lead", System.currentTimeMillis());
        store.save(team, List.of());
        runtimes.put(flat, new TeamRuntime(team, store.teamDir(flat)));
        currentTeamName = flat;
        return team;
    }

    /** 删除团队：终止全部队员、清理隔离 worktree（有变更保留通知）、删除配置。 */
    public boolean deleteTeam(String name) {
        TeamRuntime rt;
        synchronized (this) {
            rt = runtimes.remove(name);
            if (rt == null) {
                rt = retiringRuntimes.stream().filter(retiring -> retiring.team().name().equals(name))
                        .findFirst().orElse(null);
            }
            if (rt == null) {
                Optional<TeamStore.StoredTeam> loaded = store.load(name);
                if (loaded.isEmpty()) return false;
                rt = new TeamRuntime(loaded.get().team(), store.teamDir(name));
                for (TeamMember m : loaded.get().roster()) rt.putMember(m);
            }
            if (!retiringRuntimes.contains(rt)) retiringRuntimes.add(rt);
            for (TeamMember m : List.copyOf(rt.members())) terminate(rt, m);
            if (name.equals(currentTeamName)) currentTeamName = null;
        }
        // 完成回调也需要管理器锁；必须先释放锁，再等待线程停止写入。
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (rt.hasActiveWork()) {
            if (System.nanoTime() >= deadline) throw new TeamException("队员仍在收尾，团队文件已保留，请稍后重试删除");
            try { Thread.sleep(10); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TeamException("删除被中断，团队文件已保留");
            }
        }
        if (worktreeManager != null) {
            for (TeamMember m : rt.members()) {
                if (m.isolation()) {
                    var r = worktreeManager.autoCleanup(m.name());
                    if (notifier != null) {
                        notifier.accept("清理隔离目录 " + m.name() + ": " + r.message());
                    }
                }
            }
        }
        store.delete(name);
        synchronized (this) { retiringRuntimes.remove(rt); }
        return true;
    }

    /** 磁盘上全部团队（含未在本会话创建的）。 */
    public List<Team> listTeams() {
        return store.list();
    }

    public Optional<Team> findTeam(String name) {
        return store.load(name).map(TeamStore.StoredTeam::team);
    }

    /** 指定团队的花名册（含未在本会话加载的团队）。 */
    public Optional<List<TeamMember>> roster(String name) {
        return store.load(name).map(TeamStore.StoredTeam::roster);
    }

    /** 当前团队名；无团队返回 null。 */
    public String currentTeamName() {
        return currentTeamName;
    }

    /** 切换当前团队（供 /team use 与测试）。 */
    public synchronized boolean useTeam(String name) {
        if (store.load(name).isEmpty()) {
            throw new TeamException("团队不存在: " + name);
        }
        currentTeamName = name;
        return true;
    }

    /** 当前团队运行期容器（懒加载磁盘团队）；无当前团队返回空。 */
    public synchronized Optional<TeamRuntime> currentTeam() {
        String name = currentTeamName;
        if (name == null) return Optional.empty();
        TeamRuntime rt = runtimes.get(name);
        if (rt != null) {
            store.load(name).ifPresent(stored -> stored.roster().forEach(rt::putMember));
            return Optional.of(rt);
        }
        return Optional.ofNullable(runtimes.computeIfAbsent(name, n -> {
            Optional<TeamStore.StoredTeam> loaded = store.load(n);
            if (loaded.isEmpty()) return null;
            TeamRuntime runtime = new TeamRuntime(loaded.get().team(), store.teamDir(n));
            for (TeamMember m : loaded.get().roster()) {
                runtime.putMember(m);
            }
            return runtime;
        }));
    }

    private TeamRuntime requireCurrent() {
        return currentTeam().orElseThrow(() -> new TeamException("当前没有团队，请先 TeamCreate"));
    }

    // ---- 队员派生 / 终止 ----

    /** 派生队员：Slug 校验 + 重名拒绝 → 后端检测（不静默降级）→ isolation 建 worktree → 启动。 */
    public synchronized TeamMember spawnMember(SpawnRequest req) {
        TeamRuntime rt = requireCurrent();
        String name = TeamStore.sanitize(req.name());
        if (rt.member(name).isPresent()) {
            throw new TeamException("队员名已存在: " + name);
        }
        BackendType backend = detector.detect(req.backend());
        String agentId = "m-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String workDirStr = baseContext.workingDirectory().toString();
        if (req.isolation() && worktreeManager != null
                && GitCommand.isGitRepository(baseContext.workingDirectory())) {
            var wt = worktreeManager.createIsolated(name);
            if (wt.success() && wt.info() != null) {
                workDirStr = wt.info().path().toString();
            } else if (notifier != null) {
                notifier.accept("隔离目录创建失败，降级共享目录: " + wt.message());
            }
        }
        int maxRounds = req.maxRounds() > 0 ? req.maxRounds() : AgentDefinition.defaultMaxRounds();
        String model = req.model() == null || req.model().isBlank() ? null : req.model();
        TeamMember member = new TeamMember(agentId, name, req.role(), MemberStatus.RUNNING,
                workDirStr, backend, req.needApproval(), req.isolation(),
                model, maxRounds, System.currentTimeMillis());
        persist(rt, member);
        rt.putMember(member);
        if (backend != BackendType.INPROCESS) externalMemberStarted = true;
        MemberHandle handle = switch (backend) {
            case INPROCESS -> inProcessBackend.spawn(rt, member, req.task());
            case TMUX -> tmuxBackend.spawn(rt, member, req.task());
            case ITERM2 -> iterm2Backend.spawn(rt, member, req.task());
            default -> throw new TeamException("未支持的后端: " + backend);
        };
        rt.putHandle(member.agentId(), handle);
        return member;
    }

    /** 终止当前团队某队员；不存在抛可读错误。 */
    public synchronized boolean terminateMember(String name) {
        TeamRuntime rt = requireCurrent();
        TeamMember m = rt.member(name).orElseThrow(() -> new TeamException("队员不存在: " + name));
        return terminate(rt, m);
    }

    private boolean terminate(TeamRuntime rt, TeamMember m) {
        if (m.status() == MemberStatus.TERMINATED) return false;
        switch (m.backend()) {
            case INPROCESS -> inProcessBackend.stop(rt, m);
            case TMUX -> tmuxBackend.stop(rt, m);
            case ITERM2 -> iterm2Backend.stop(rt, m);
            default -> { }
        }
        TeamMember terminated = m.withStatus(MemberStatus.TERMINATED);
        persist(rt, terminated);
        rt.putMember(terminated);
        return true;
    }

    /** 队员自然停止回调：置 IDLE、落盘、发 lifecycle 邮件、通知主对话；全员空闲发收敛消息。 */
    synchronized void onMemberFinished(TeamRuntime rt, TeamMember member, SubagentResult result) {
        if (rt.member(member.name()).map(m -> m.status() == MemberStatus.TERMINATED)
                .orElse(true)) return;
        TeamMember idle = member.withStatus(MemberStatus.IDLE);
        if (!persist(rt, idle)) {
            store.load(rt.team().name()).ifPresent(stored -> stored.roster().forEach(rt::putMember));
            return;
        }
        rt.putMember(idle);
        rt.mailbox().send(member.agentId(), "lead", MessageType.LIFECYCLE,
                "队员空闲", summary(result), Map.of("event", "IDLE"));
        if (notifier != null) {
            notifier.accept("队员 " + member.name() + " 已空闲: " + summary(result));
        }
        if (rt.members().stream().noneMatch(m -> m.status() == MemberStatus.RUNNING)) {
            rt.mailbox().send("system", "lead", MessageType.LIFECYCLE,
                    "团队收敛", "全部队员已空闲，可执行 TeamMerge", Map.of("event", "ALL_IDLE"));
        }
    }

    // ---- 消息与续写 ----

    /**
     * 发送消息：名称注册表解析（all=广播）→ 写邮箱 → 目标后端唤醒/恢复。
     * 目标为 INPROCESS 且 IDLE 时触发从磁盘 transcript 恢复续写。
     */
    public String sendMessage(TeamRuntime rt, String from, String to, MessageType type,
                              String subject, String body, Map<String, String> protocol) {
        if (type == MessageType.BROADCAST || "all".equalsIgnoreCase(to)) {
            List<String> targets = rt.members().stream()
                    .map(TeamMember::agentId).filter(id -> !id.equals(from)).toList();
            rt.mailbox().broadcast(from, targets, MessageType.BROADCAST, subject, body, protocol);
            return "已广播给 " + targets.size() + " 名队员";
        }
        TeamMember target = rt.member(to).orElseThrow(() -> new TeamException("未知队员: " + to));
        rt.mailbox().send(from, target.agentId(), type, subject, body, protocol);
        switch (target.backend()) {
            case TMUX -> tmuxBackend.wake(rt, target);
            case ITERM2 -> iterm2Backend.wake(rt, target);
            case INPROCESS -> {
                if (target.status() == MemberStatus.IDLE) {
                    String wakeMessage = body == null || body.isBlank()
                            ? "收到新邮件，请先读取并核对原请求后继续。" : body;
                    resumeMember(rt, target, wakeMessage);
                }
            }
            default -> { }
        }
        return "消息已发送给 " + target.name();
    }

    /** IDLE 队员恢复：transcript 恢复会话 + 以消息为新输入续写。 */
    private synchronized void resumeMember(TeamRuntime rt, TeamMember member, String message) {
        TeamMember current = rt.member(member.name()).orElse(null);
        if (current == null || current.status() != MemberStatus.IDLE) return;
        TeamMember running = current.withStatus(MemberStatus.RUNNING);
        if (!persist(rt, running)) return;
        rt.putMember(running);
        Thread worker = Thread.ofVirtual().name("zencode-member-resume-" + member.name()).unstarted(() -> {
            try {
                List<Tool> extra = List.of(new TeamTaskTool(rt),
                        new SendMessageTool(rt, this, member.agentId()));
                SubagentResult result = memberRunner.resumeMember(rt, member, message, baseContext, extra);
                onMemberFinished(rt, member, result);
            } finally {
                rt.removeWorker(member.agentId(), Thread.currentThread());
            }
        });
        rt.putWorker(member.agentId(), worker);
        try {
            worker.start();
        } catch (RuntimeException | Error failure) {
            rt.removeWorker(member.agentId(), worker);
            TeamMember idle = current.withStatus(MemberStatus.IDLE);
            persist(rt, idle);
            rt.putMember(idle);
            throw failure;
        }
    }

    // ---- Coordinator Mode（双锁：配置锁 + 运行时锁） ----

    /** 激活运行时锁；配置锁缺失抛可读错误。 */
    public boolean enableCoordinator() {
        TeamRuntime rt = requireCurrent();
        if (!config.coordinatorEnabled()) {
            throw new TeamException("配置未开启 coordinator.enabled（第一锁缺失），无法激活 Coordinator Mode");
        }
        rt.setCoordinatorRuntime(true);
        return true;
    }

    public boolean disableCoordinator() {
        requireCurrent().setCoordinatorRuntime(false);
        return true;
    }

    /** Coordinator Mode 是否生效（双锁同时满足）。 */
    public boolean coordinatorActive() {
        Optional<TeamRuntime> rt = currentTeam();
        return rt.isPresent() && config.coordinatorEnabled() && rt.get().isCoordinatorRuntime();
    }

    // ---- 收敛合并 ----

    /**
     * 收敛合并：对 isolation 队员的独立分支执行 git merge 回主分支。
     * 冲突时执行 git merge --abort，停止合并并上报，保留队员分支供人工解决。
     */
    public synchronized MergeResult mergeTeam() {
        TeamRuntime rt = requireCurrent();
        List<String> merged = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        List<String> rolledBack = new ArrayList<>();
        for (TeamMember m : rt.members()) {
            if (!m.isolation() || m.status() == MemberStatus.TERMINATED) continue;
            String branch = findBranch(m);
            if (branch == null) {
                conflicts.add(m.name() + "(无分支信息)");
                break;
            }
            GitResult merge = GitCommand.run(workDir, "merge", "--no-ff", "-m",
                    "TeamMerge: " + branch, branch);
            if (merge.ok()) {
                merged.add(branch);
                continue;
            }
            conflicts.add(branch);
            GitResult mergeHead = GitCommand.run(workDir, "rev-parse", "--verify", "MERGE_HEAD");
            if (mergeHead.ok()) {
                GitResult abort = GitCommand.run(workDir, "merge", "--abort");
                if (!abort.ok()) {
                    return new MergeResult(false,
                            "合并 " + branch + " 失败，自动回滚也失败；请先检查仓库的合并状态",
                            merged, conflicts, rolledBack);
                }
                rolledBack.add(branch);
            }
            break;
        }
        if (merged.isEmpty() && conflicts.isEmpty()) {
            return MergeResult.fail("没有可合并的隔离队员分支（或团队无 isolation 队员）");
        }
        String message = String.format("收敛合并: %d 个分支已合并, %d 个需人工处理, %d 个已回滚",
                merged.size(), conflicts.size(), rolledBack.size());
        if (!conflicts.isEmpty()) {
            message += "；请检查冲突分支并人工解决后重试";
        }
        return new MergeResult(conflicts.isEmpty(), message, merged, conflicts, rolledBack);
    }

    /** 按队员工作目录从 worktree 列表找分支。 */
    private String findBranch(TeamMember m) {
        if (worktreeManager == null) return null;
        Path target = Path.of(m.workDir());
        for (WorktreeInfo info : worktreeManager.list()) {
            if (info.path().equals(target)) {
                return info.branch();
            }
        }
        return null;
    }

    // ---- 内部 ----

    private boolean persist(TeamRuntime rt, TeamMember member) {
        return store.upsertMember(rt.team(), member);
    }

    private static String summary(SubagentResult result) {
        if (result == null) return "无结果";
        String output = result.output() == null ? "" : result.output();
        return output.length() > 120 ? output.substring(0, 120) + "…" : output;
    }
}
