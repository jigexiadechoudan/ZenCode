package dev.zencode.worktree;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 后台周期清理调度器：周期性调用 {@link WorktreeManager#staleCleanup()}，不阻塞主流程。
 */
public final class WorktreeCleanupTask {

    private final WorktreeManager manager;
    private final long intervalSeconds;
    private ScheduledExecutorService executor;

    public WorktreeCleanupTask(WorktreeManager manager, long intervalSeconds) {
        this.manager = manager;
        this.intervalSeconds = intervalSeconds;
    }

    /** 启动周期调度。 */
    public void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().name("zencode-worktree-cleanup").unstarted(r);
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(manager::staleCleanup,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /** 停止调度。 */
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
