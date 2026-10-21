package dev.zencode.subagent;

import dev.zencode.agent.AgentRun;
import dev.zencode.chat.Usage;

import java.time.Instant;

/**
 * 后台任务的可变状态，由 {@link TaskManager} 集中维护，多线程安全。
 */
public final class TaskState {
    private final String id;
    private final String description;
    private final Instant startTime;
    private volatile TaskStatus status;
    private volatile String result;
    private volatile Usage usage;
    private volatile Instant endTime;
    private volatile AgentRun run;
    private volatile Thread worker;

    TaskState(String id, String description) {
        this.id = id;
        this.description = description == null ? "" : description;
        this.startTime = Instant.now();
        this.status = TaskStatus.RUNNING;
        this.result = "";
        this.usage = Usage.unknown();
    }

    public String id() { return id; }

    public String description() { return description; }

    public TaskStatus status() { return status; }

    public String result() { return result; }

    public Usage usage() { return usage; }

    public Instant startTime() { return startTime; }

    public Instant endTime() { return endTime; }

    public AgentRun run() { return run; }
    Thread worker() { return worker; }

    /** 写入终态与用量；幂等性由调用方保证（仅 RUNNING 时写）。 */
    synchronized boolean complete(TaskStatus status, String result, Usage usage) {
        if (this.status != TaskStatus.RUNNING) return false;
        this.status = status;
        this.result = result == null ? "" : result;
        this.usage = usage == null ? Usage.unknown() : usage;
        this.endTime = Instant.now();
        return true;
    }

    /** 关联运行中实例（前台↔后台移交用）。 */
    synchronized void markRunning(AgentRun run) {
        this.run = run;
        if (status != TaskStatus.RUNNING && run != null) run.cancel();
    }

    synchronized void attachWorker(Thread worker) {
        this.worker = worker;
    }
}
