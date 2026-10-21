package dev.zencode.tool;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具执行上下文，包含工作目录、调用 ID 和共享的运行取消信号。
 * <p>
 * 同一 Agent 运行内的多个工具调用共享同一个取消信号（{@link AtomicBoolean}），
 * 使批处理器和单个工具观察相同的取消状态；每次调用仍保留自己的调用 ID。
 * 短文件操作在执行前后检查；长时间命令执行周期检查并响应线程中断。
 * </p>
 */
public final class ToolExecutionContext {
    private final Path workingDirectory;
    private final String callId;
    private final AtomicBoolean cancelled;
    private final boolean planOnly;

    public ToolExecutionContext(Path workingDirectory, String callId) {
        this(workingDirectory, callId, new AtomicBoolean(false));
    }

    /** 创建与外部共享取消信号的执行上下文。 */
    public ToolExecutionContext(Path workingDirectory, String callId, AtomicBoolean cancelled) {
        this(workingDirectory, callId, cancelled, false);
    }

    public ToolExecutionContext(Path workingDirectory, String callId, AtomicBoolean cancelled,
                                boolean planOnly) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "工作目录不能为空");
        this.callId = Objects.requireNonNull(callId, "调用 ID 不能为空");
        this.cancelled = Objects.requireNonNull(cancelled, "取消信号不能为空");
        this.planOnly = planOnly;
    }

    /** 规范化后的当前工作目录，所有路径工具以此为根。 */
    public Path workingDirectory() {
        return workingDirectory;
    }

    /** 模型分配的工具调用 ID，用于关联结果和历史。 */
    public String callId() {
        return callId;
    }

    /** 检查是否已被取消。 */
    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean planOnly() { return planOnly; }

    /** 标记取消。多次调用安全；共享信号对所有绑定上下文生效。 */
    public void cancel() {
        cancelled.set(true);
    }
}
