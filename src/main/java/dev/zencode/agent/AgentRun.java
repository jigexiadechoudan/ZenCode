package dev.zencode.agent;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 一次正在进行或已经完成的 Agent 运行。
 * <p>
 * 事件流有界并提供背压；取消和终止不能因队列已满而永久阻塞，终止信号必须可观测。
 * {@link #takeEvent()} 阻塞等待下一个事件；{@link #pollEvent(Duration)} 带超时等待。
 * 消费者观察到的最后一个事件始终是 {@link AgentEvent.Terminated}。
 * {@link #cancel()} 幂等，首次调用触发资源取消；{@link #completion()} 在终止时完成。
 * </p>
 */
public interface AgentRun {

    /** 阻塞等待下一个事件。终止后仍可返回唯一的 {@link AgentEvent.Terminated}。 */
    AgentEvent takeEvent() throws InterruptedException;

    /** 带超时等待下一个事件，超时返回 {@code null}。 */
    AgentEvent pollEvent(Duration timeout) throws InterruptedException;

    /** 请求取消。首次调用触发资源取消回调并发布唯一 {@code CANCELLED} 终止；重复调用无副作用。 */
    void cancel();

    /**
     * 发布一个普通事件；已终止或队列中断时返回 false。
     * 供 Agent 主循环之外的合法生产者（如 HITL 确认请求）向事件流注入事件。
     */
    boolean publish(AgentEvent event);

    /** 终止时完成，携带唯一的终止原因。 */
    CompletableFuture<AgentTerminationReason> completion();

    /** 主循环是否仍可能执行；实现可覆盖以区分取消终态与真正退出。 */
    default boolean isProducerRunning() {
        return !completion().isDone();
    }
}
