package dev.zencode.agent.execution;

import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolResult;

import java.util.List;
import java.util.Objects;

/**
 * 按注册顺序执行的拦截器链。
 * <p>
 * 默认放行：空链或所有 before 均返回 {@link ToolExecutionInterceptor.BeforeDecision.Allow} 时执行工具。
 * 任一 before 返回 {@link ToolExecutionInterceptor.BeforeDecision.Reject} 时短路拒绝，后续拦截器不再执行。
 * before 异常转换为当前调用的安全失败结果并停止放行；after 异常转换为安全失败结果。
 * 异常堆栈不进入结果内容。
 * </p>
 */
public final class ToolInterceptorChain implements ToolExecutionInterceptor {
    private final List<ToolExecutionInterceptor> interceptors;

    public ToolInterceptorChain(List<ToolExecutionInterceptor> interceptors) {
        this.interceptors = List.copyOf(Objects.requireNonNull(interceptors, "拦截器列表不能为空"));
    }

    /** 空拦截器链：默认全部放行。 */
    public static ToolInterceptorChain empty() {
        return new ToolInterceptorChain(List.of());
    }

    @Override
    public BeforeDecision before(ToolCall call, ToolExecutionContext context) {
        for (ToolExecutionInterceptor interceptor : interceptors) {
            BeforeDecision decision;
            try {
                decision = interceptor.before(call, context);
            } catch (RuntimeException e) {
                return new BeforeDecision.Reject(safeFailure(call, "拦截器执行前异常"));
            }
            if (decision instanceof BeforeDecision.Reject reject) {
                return reject;
            }
        }
        return new BeforeDecision.Allow();
    }

    @Override
    public BeforeDecision revalidate(ToolCall call, ToolExecutionContext context) {
        for (ToolExecutionInterceptor interceptor : interceptors) {
            try {
                BeforeDecision decision = interceptor.revalidate(call, context);
                if (decision instanceof BeforeDecision.Reject) return decision;
            } catch (RuntimeException e) {
                return new BeforeDecision.Reject(safeFailure(call, "执行前复核异常"));
            }
        }
        return new BeforeDecision.Allow();
    }

    @Override
    public ToolResult after(ToolCall call, ToolResult result, ToolExecutionContext context) {
        ToolResult current = result;
        for (ToolExecutionInterceptor interceptor : interceptors) {
            try {
                current = interceptor.after(call, current, context);
            } catch (RuntimeException e) {
                return safeFailure(call, "拦截器执行后异常");
            }
        }
        return current;
    }

    private static ToolResult safeFailure(ToolCall call, String message) {
        return ToolResult.error(call.callId(), call.toolName(), message);
    }
}
