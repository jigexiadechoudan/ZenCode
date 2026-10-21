package dev.zencode.subagent;

import dev.zencode.chat.Usage;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 子 Agent 调用链追踪节点。
 * <p>
 * 标识、父标识、标签与启动时刻在创建时固定；状态、用量、终止时刻与子节点列表可变，
 * 用 volatile 字段 + CopyOnWriteArrayList 保证多线程读写一致。
 * </p>
 */
public final class TraceNode {
    private final String id;
    private final String parentId;
    private final String label;
    private final Instant startTime;
    private volatile TaskStatus status;
    private volatile Usage usage;
    private volatile Instant endTime;
    private final CopyOnWriteArrayList<String> childrenIds = new CopyOnWriteArrayList<>();

    TraceNode(String id, String parentId, String label) {
        this.id = id;
        this.parentId = parentId;
        this.label = label == null ? "" : label;
        this.startTime = Instant.now();
        this.status = TaskStatus.RUNNING;
        this.usage = Usage.unknown();
    }

    public String id() { return id; }

    public String parentId() { return parentId; }

    public String label() { return label; }

    public TaskStatus status() { return status; }

    public Usage usage() { return usage; }

    public Instant startTime() { return startTime; }

    public Instant endTime() { return endTime; }

    public List<String> childrenIds() { return List.copyOf(childrenIds); }

    void addChild(String childId) { childrenIds.add(childId); }

    void finish(TaskStatus status, Usage usage) {
        this.status = status;
        this.usage = usage == null ? Usage.unknown() : usage;
        this.endTime = Instant.now();
    }
}
