package dev.zencode.subagent;

import dev.zencode.agent.AgentRun;
import dev.zencode.chat.Usage;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 后台任务管理器：后台启动、状态查询、取消、自动超时、完成通知。
 * <p>
 * 完成通知通过 {@code completionNotifier} 回调异步注入主对话（不打断当前对话）。
 * </p>
 */
public final class TaskManager {
    private final ConcurrentMap<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final Consumer<TaskState> completionNotifier;
    private final Duration defaultTimeout;

    public TaskManager() {
        this(null, null);
    }

    /**
     * @param completionNotifier 完成通知回调（可空）
     * @param defaultTimeout     默认自动超时（可空=不超时）
     */
    public TaskManager(Consumer<TaskState> completionNotifier, Duration defaultTimeout) {
        this.completionNotifier = completionNotifier;
        this.defaultTimeout = defaultTimeout;
    }

    /** 提交后台任务，返回 taskId；body 在独立虚拟线程执行。 */
    public String submit(String description, Supplier<SubagentResult> body) {
        Objects.requireNonNull(body, "任务执行体不能为空");
        return submitWithRun(description, ignored -> body.get());
    }

    /** 执行体创建 AgentRun 时必须立即回传，使取消/超时能停止 Provider 与工具。 */
    public String submitWithRun(String description,
                                Function<Consumer<AgentRun>, SubagentResult> body) {
        Objects.requireNonNull(body, "任务执行体不能为空");
        String id = UUID.randomUUID().toString();
        TaskState state = new TaskState(id, description);
        tasks.put(id, state);
        Thread worker = Thread.ofVirtual().name("zencode-task-" + id)
                .unstarted(() -> execute(state, () -> body.apply(run -> state.markRunning(run))));
        state.attachWorker(worker);
        try {
            worker.start();
        } catch (RuntimeException | Error failure) {
            state.attachWorker(null);
            state.complete(TaskStatus.FAILED, "后台任务启动失败", Usage.unknown());
            notifyComplete(state);
            throw failure;
        }
        return id;
    }

    private void execute(TaskState state, Supplier<SubagentResult> body) {
        if (state.status() != TaskStatus.RUNNING) return;
        Thread watchdog = startWatchdog(state);
        try {
            SubagentResult result = body.get();
            if (state.complete(result.status(), result.output(), result.usage())) notifyComplete(state);
        } catch (RuntimeException e) {
            if (state.complete(TaskStatus.FAILED, "执行异常: " + e.getMessage(), Usage.unknown())) {
                notifyComplete(state);
            }
        } finally {
            if (watchdog != null) {
                watchdog.interrupt();
            }
        }
    }

    /** 超时 watchdog：超时且仍运行则标记 TIMEOUT。 */
    private Thread startWatchdog(TaskState state) {
        if (defaultTimeout == null || defaultTimeout.isZero() || defaultTimeout.isNegative()) {
            return null;
        }
        long millis = defaultTimeout.toMillis();
        return Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                return;
            }
            stop(state, TaskStatus.TIMEOUT, "任务超时（" + millis + "ms）");
        });
    }

    private void notifyComplete(TaskState state) {
        if (completionNotifier != null) {
            try {
                completionNotifier.accept(state);
            } catch (RuntimeException ignored) {
                // 通知异常不阻断任务收尾
            }
        }
    }

    /** 前台运行中实例移交后台（不重建）。 */
    public void attachRun(String id, AgentRun run) {
        TaskState state = tasks.get(id);
        if (state != null) {
            state.markRunning(run);
        }
    }

    public List<TaskState> list() {
        return List.copyOf(tasks.values());
    }

    /** 终态并不代表工作线程/Agent 主循环已退出，切换配置前需同时检查。 */
    public boolean hasActiveTasks() {
        return tasks.values().stream().anyMatch(state -> {
            Thread worker = state.worker();
            AgentRun run = state.run();
            return state.status() == TaskStatus.RUNNING
                    || worker != null && worker.getState() != Thread.State.TERMINATED
                    || run != null && run.isProducerRunning();
        });
    }

    public Optional<TaskState> find(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    /** 取消运行中任务；已终止返回 false。 */
    public boolean cancel(String id) {
        TaskState state = tasks.get(id);
        return state != null && stop(state, TaskStatus.CANCELLED, "任务已取消");
    }

    private boolean stop(TaskState state, TaskStatus terminal, String message) {
        AgentRun run = state.run();
        Thread worker = state.worker();
        if (!state.complete(terminal, message, state.usage())) return false;
        if (run != null) {
            try {
                run.cancel();
            } catch (RuntimeException ignored) {
                // 即使 Provider 取消失败，仍需中断任务线程。
            }
        }
        if (worker != null) worker.interrupt();
        notifyComplete(state);
        return true;
    }
}
