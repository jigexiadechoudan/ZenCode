package dev.zencode.agent.execution;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具批执行上下文。
 * <p>
 * 包含工作目录、运行取消信号、plan-only 状态、拦截器链和工具事件发布入口。
 * 同一运行内的所有工具调用共享同一个取消信号。
 * </p>
 */
public record ToolBatchContext(
        Path workingDirectory,
        AtomicBoolean cancelled,
        boolean planOnly,
        ToolExecutionInterceptor interceptor,
        ToolEventSink events,
        int round) {

    public ToolBatchContext {
        Objects.requireNonNull(workingDirectory, "工作目录不能为空");
        Objects.requireNonNull(cancelled, "取消信号不能为空");
        Objects.requireNonNull(interceptor, "拦截器链不能为空");
        Objects.requireNonNull(events, "事件发布入口不能为空");
    }

    /** 创建普通模式的批上下文，使用默认放行拦截器链与空事件入口。 */
    public static ToolBatchContext normal(Path workingDirectory, int round) {
        return new ToolBatchContext(workingDirectory, new AtomicBoolean(false), false,
                ToolInterceptorChain.empty(), ToolEventSink.NOOP, round);
    }
}
