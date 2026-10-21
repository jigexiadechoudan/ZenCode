package dev.zencode.agent;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 有界 AgentRun 实现。
 * <p>
 * 正常事件由唯一主循环通过 {@link #publish(AgentEvent)} 生产，使用有界队列提供背压。
 * 终止事件 {@link AgentEvent.Terminated} 通过独立的原子保留槽位发布，
 * 即使普通队列已满也始终可被消费者观察到。消费者以短周期轮询同时检查保留槽位和队列，
 * 因此终止可以中断对队列的阻塞等待，测试不会挂起。
 * </p>
 * <p>
 * 终止只发生一次（CAS 保证）：首次 {@link #cancel()} 触发资源取消回调并中断主循环线程，
 * 再发布唯一 {@code CANCELLED} 终止；终止后普通事件和迟到终止事件均被拒绝。
 * </p>
 */
public final class AgentRunImpl implements AgentRun {
    private final ArrayBlockingQueue<AgentEvent> queue;
    private final AtomicReference<AgentEvent> reservedTerminal = new AtomicReference<>();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final CompletableFuture<AgentTerminationReason> completion = new CompletableFuture<>();
    private volatile Runnable cancelCallback;
    private volatile Thread producerThread;
    private volatile int completedRounds;

    public AgentRunImpl(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("事件缓冲容量必须为正数: " + capacity);
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** 绑定取消时的资源清理回调（由 Agent 提供，用于取消 Provider、工具和命令进程）。 */
    public void setCancelCallback(Runnable cancelCallback) {
        this.cancelCallback = cancelCallback;
    }

    /** 绑定主循环线程，取消时中断其可能阻塞的发布与执行。 */
    public void setProducerThread(Thread producerThread) {
        this.producerThread = producerThread;
    }

    /** 每提交一个完整轮次后调用，记录已完成轮数。 */
    public void noteRoundCommitted() {
        completedRounds++;
    }

    /** 是否已收到至少一次取消请求（幂等）。 */
    public boolean isCancelRequested() {
        return cancelRequested.get();
    }

    /** 发布一个普通事件；已终止时拒绝并返回 false。 */
    public boolean publish(AgentEvent event) {
        if (event == null) return false;
        if (event instanceof AgentEvent.Terminated terminatedEvent) {
            return publishTerminated(terminatedEvent.reason());
        }
        if (terminated.get()) return false;
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !terminated.get();
    }

    /**
     * 发布唯一终止事件并完成 completion。只生效一次；重复调用返回 false。
     * 终止事件写入独立保留槽位，不占用普通队列容量。
     */
    public boolean publishTerminated(AgentTerminationReason reason) {
        if (!terminated.compareAndSet(false, true)) return false;
        AgentEvent.Terminated terminal = new AgentEvent.Terminated(reason, completedRounds);
        reservedTerminal.set(terminal);
        completion.complete(reason);
        return true;
    }

    @Override
    public AgentEvent takeEvent() throws InterruptedException {
        while (true) {
            AgentEvent event = queue.poll(20, TimeUnit.MILLISECONDS);
            if (event != null) return event;
            AgentEvent terminal = reservedTerminal.get();
            if (terminal != null) return terminal;
        }
    }

    @Override
    public AgentEvent pollEvent(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            AgentEvent event = queue.poll(Math.min(remaining(deadline), 20_000_000L), TimeUnit.NANOSECONDS);
            if (event != null) return event;
            AgentEvent terminal = reservedTerminal.get();
            if (terminal != null) return terminal;
            if (remaining(deadline) <= 0) return null;
        }
    }

    private static long remaining(long deadline) {
        return deadline - System.nanoTime();
    }

    @Override
    public void cancel() {
        if (!cancelRequested.compareAndSet(false, true)) return;
        Runnable callback = cancelCallback;
        if (callback != null) {
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // 资源清理失败不影响取消语义
            }
        }
        Thread producer = producerThread;
        if (producer != null) producer.interrupt();
        publishTerminated(AgentTerminationReason.CANCELLED);
    }

    @Override
    public CompletableFuture<AgentTerminationReason> completion() {
        return completion;
    }

    @Override
    public boolean isProducerRunning() {
        Thread producer = producerThread;
        return producer != null ? producer.getState() != Thread.State.TERMINATED
                : !completion.isDone();
    }
}
