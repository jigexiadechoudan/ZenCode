package dev.zencode.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * worktree 生命周期核心：Create / Enter / Exit / Remove / List / AutoCleanup / StaleCleanup。
 * <p>
 * 所有变更操作 {@code synchronized}，保证同一 worktree 不会被并发重复创建/删除（N2）。
 * 变更检测复用 {@link GitCommand#detectChanges}（fail-closed）。
 * </p>
 */
public final class WorktreeManager {

    private static final System.Logger LOG = System.getLogger("dev.zencode.worktree.WorktreeManager");
    private static final int MAX_STALE_CLEANUP = 20;

    private final Path repoRoot;
    private final WorktreeConfig config;
    private final WorktreeSessionStore store;
    private volatile Runnable cacheInvalidator;
    private volatile java.util.function.BooleanSupplier directoryChangeAllowed = () -> true;
    private final ConcurrentMap<String, WorktreeInfo> worktrees = new ConcurrentHashMap<>();

    private volatile Path currentDirectory;
    private volatile String currentName;

    public WorktreeManager(Path repoRoot, WorktreeConfig config, WorktreeSessionStore store,
                           Runnable cacheInvalidator) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.config = config == null ? WorktreeConfig.defaults() : config;
        this.store = store;
        this.cacheInvalidator = cacheInvalidator == null ? () -> {} : cacheInvalidator;
        this.currentDirectory = this.repoRoot;
        Optional<WorktreeSession> session = store == null ? Optional.empty() : store.load();
        session.ifPresent(this::restoreSession);
    }

    /** 当前生效工作目录。 */
    public Path currentDirectory() {
        return currentDirectory;
    }

    /** 装配期注入/替换缓存失效回调（切换目录时触发环境组件重建）。 */
    public void setCacheInvalidator(Runnable cacheInvalidator) {
        this.cacheInvalidator = cacheInvalidator == null ? () -> {} : cacheInvalidator;
    }

    public void setDirectoryChangeAllowed(java.util.function.BooleanSupplier allowed) {
        directoryChangeAllowed = java.util.Objects.requireNonNull(allowed);
    }

    /** 当前 worktree 名；null=主仓库。 */
    public String currentName() {
        return currentName;
    }

    /** 手动创建 worktree（/worktree create）。 */
    public synchronized WorktreeOperationResult create(String name) {
        return createInternal(name, "", false, "manual");
    }

    /** 子 Agent 隔离创建 worktree（tempPrefix + isolation=true + source=subagent）。 */
    public synchronized WorktreeOperationResult createIsolated(String name) {
        return createInternal(name, config.tempPrefix(), true, "subagent");
    }

    private WorktreeOperationResult createInternal(String name, String prefix,
                                                   boolean isolation, String source) {
        if (!isolation && !directoryChangeAllowed.getAsBoolean()) {
            return WorktreeOperationResult.fail("仍有任务在运行，不能切换工作目录");
        }
        final String slug;
        try {
            slug = SlugValidator.validate(name);
        } catch (IllegalArgumentException e) {
            return WorktreeOperationResult.fail(e.getMessage());
        }
        String fullName = prefix + slug;
        Path target = worktreesRoot().resolve(fullName);

        // 快速恢复：目录已存在且含 .git
        if (Files.isDirectory(target) && Files.exists(target.resolve(".git"))) {
            return quickRestore(fullName, target, isolation, source);
        }

        try {
            Files.createDirectories(worktreesRoot());
        } catch (IOException e) {
            return WorktreeOperationResult.fail("无法创建 worktree 根目录: " + e.getMessage());
        }

        GitCommand.GitResult r = GitCommand.worktreeAdd(repoRoot, target.toString(), fullName);
        if (!r.ok()) {
            return WorktreeOperationResult.fail("创建 worktree 失败: " + r.stderr().trim());
        }
        WorktreeInitializer.initialize(repoRoot, target, config);
        WorktreeInfo info = new WorktreeInfo(fullName, target, fullName,
                WorktreeInitializer.readHead(target), Instant.now(), isolation, source);
        worktrees.put(fullName, info);
        if (!isolation) enterInternal(fullName, target);
        persist();
        return WorktreeOperationResult.ok((isolation ? "已创建隔离 worktree: " : "已创建并进入 worktree: ") + fullName, info);
    }

    private WorktreeOperationResult quickRestore(String fullName, Path target,
                                                 boolean isolation, String source) {
        WorktreeInfo info = new WorktreeInfo(fullName, target, fullName,
                WorktreeInitializer.readHead(target), Instant.now(), isolation, source);
        worktrees.put(fullName, info);
        if (!isolation) enterInternal(fullName, target);
        persist();
        return WorktreeOperationResult.ok("已恢复已存在的 worktree: " + fullName, info);
    }

    /** 进入 worktree。 */
    public synchronized WorktreeOperationResult enter(String name) {
        if (!directoryChangeAllowed.getAsBoolean()) {
            return WorktreeOperationResult.fail("仍有任务在运行，不能切换工作目录");
        }
        final String slug;
        try {
            slug = SlugValidator.validate(name);
        } catch (IllegalArgumentException e) {
            return WorktreeOperationResult.fail(e.getMessage());
        }
        Path target = worktreesRoot().resolve(slug);
        if (!Files.isDirectory(target)) {
            return WorktreeOperationResult.fail("worktree 不存在: " + slug);
        }
        enterInternal(slug, target);
        persist();
        return WorktreeOperationResult.ok("已进入 worktree: " + slug, worktrees.get(slug));
    }

    /** 切回主仓库。 */
    public synchronized WorktreeOperationResult exit() {
        if (!directoryChangeAllowed.getAsBoolean()) {
            return WorktreeOperationResult.fail("仍有任务在运行，不能切换工作目录");
        }
        currentName = null;
        currentDirectory = repoRoot;
        cacheInvalidator.run();
        persist();
        return WorktreeOperationResult.ok("已切回主仓库", null);
    }

    /** 删除 worktree，带变更保护（discard=true 强制丢弃）。 */
    public synchronized WorktreeOperationResult remove(String name, boolean discard) {
        final String slug;
        try {
            slug = SlugValidator.validate(name);
        } catch (IllegalArgumentException e) {
            return WorktreeOperationResult.fail(e.getMessage());
        }
        Path target = worktreesRoot().resolve(slug);
        if (!Files.isDirectory(target)) {
            return WorktreeOperationResult.fail("worktree 不存在: " + slug);
        }
        if (slug.equals(currentName) && !directoryChangeAllowed.getAsBoolean()) {
            return WorktreeOperationResult.fail("仍有任务在运行，不能切换工作目录");
        }
        ChangeStatus status = GitCommand.detectChanges(target);
        if (status.dirty() && !discard) {
            return WorktreeOperationResult.fail(
                    "worktree 有未提交修改或未推送 commit，拒绝删除；如需丢弃请显式确认");
        }
        GitCommand.GitResult r = GitCommand.worktreeRemove(repoRoot, target.toString(), discard);
        if (!r.ok()) {
            return WorktreeOperationResult.fail("删除 worktree 失败: " + r.stderr().trim());
        }
        worktrees.remove(slug);
        if (slug.equals(currentName)) {
            currentName = null;
            currentDirectory = repoRoot;
            cacheInvalidator.run();
        }
        persist();
        return WorktreeOperationResult.ok("已删除 worktree: " + slug, null);
    }

    /** 列出已知 worktree。 */
    public List<WorktreeInfo> list() {
        return List.copyOf(worktrees.values());
    }

    /** 子 Agent 完成后自动清理：无变更删除，有变更保留。 */
    public synchronized WorktreeOperationResult autoCleanup(String name) {
        final String slug;
        try {
            slug = SlugValidator.validate(name);
        } catch (IllegalArgumentException e) {
            return WorktreeOperationResult.fail(e.getMessage());
        }
        Path target = worktreesRoot().resolve(slug);
        if (!Files.isDirectory(target)) {
            return WorktreeOperationResult.ok("worktree 已不存在", null);
        }
        ChangeStatus status = GitCommand.detectChanges(target);
        if (status.dirty()) {
            return WorktreeOperationResult.ok("worktree 有变更，已保留: " + slug, worktrees.get(slug));
        }
        return remove(slug, false);
    }

    /** 后台清理：三层过滤（命名匹配 → 使用中/未过期 → 变更 fail-closed）。 */
    public synchronized void staleCleanup() {
        Path root = worktreesRoot();
        if (!Files.isDirectory(root)) return;
        int cleaned = 0;
        try (var stream = Files.list(root)) {
            for (Path p : stream.filter(Files::isDirectory).toList()) {
                if (cleaned >= MAX_STALE_CLEANUP) break;
                String name = p.getFileName().toString();
                if (!name.startsWith(config.tempPrefix())) continue;      // ① 命名匹配
                if (name.equals(currentName)) continue;                   // ② 使用中
                try {
                    Instant mtime = Files.getLastModifiedTime(p).toInstant();
                    if (mtime.plusSeconds(config.staleTimeoutSeconds()).isAfter(Instant.now())) {
                        continue;                                          // ② 未过期
                    }
                } catch (IOException e) {
                    continue;
                }
                ChangeStatus status = GitCommand.detectChanges(p);
                if (status.dirty()) continue;                              // ③ 变更 fail-closed
                GitCommand.GitResult r = GitCommand.worktreeRemove(repoRoot, p.toString(), false);
                if (r.ok()) {
                    worktrees.remove(name);
                    cleaned++;
                    LOG.log(System.Logger.Level.INFO, "清理过期 worktree: " + name);
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "扫描 worktree 目录失败: " + e.getMessage());
        }
        if (cleaned > 0) persist();
    }

    private void enterInternal(String name, Path target) {
        currentName = name;
        currentDirectory = target;
        cacheInvalidator.run();
    }

    private Path worktreesRoot() {
        return repoRoot.resolve(config.worktreesRoot());
    }

    private void persist() {
        if (store != null) {
            store.save(new WorktreeSession(currentName, currentDirectory, list()));
        }
    }

    private void restoreSession(WorktreeSession session) {
        for (WorktreeInfo info : session.worktrees()) {
            worktrees.put(info.name(), info);
        }
        if (session.currentName() != null) {
            currentName = session.currentName();
            currentDirectory = session.currentPath() != null ? session.currentPath() : repoRoot;
        }
    }
}
