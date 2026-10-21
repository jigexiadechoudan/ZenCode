package dev.zencode.agent.execution;

import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolResult;

/**
 * 工具执行前后的统一拦截契约。
 * <p>
 * {@link #before} 决定调用是否放行或携带结构化结果拒绝；{@link #after} 观察或变换执行结果。
 * 任一拦截器抛出异常时由调用方转换为该调用的安全失败结果，不向事件流暴露堆栈。
 * 执行器在 before 与真正调用工具之间再次调用 revalidate，以避免审批等待期间边界变化。
 * </p>
 */
public interface ToolExecutionInterceptor {

    /** 执行前决策：放行或携带结构化结果的拒绝。 */
    BeforeDecision before(ToolCall call, ToolExecutionContext context);

    /** HITL 之后、真正执行之前重新校验不可绕过的硬边界；不得再次请求批准。 */
    default BeforeDecision revalidate(ToolCall call, ToolExecutionContext context) {
        return new BeforeDecision.Allow();
    }

    /** 执行后观察或变换结果，返回最终结果。 */
    ToolResult after(ToolCall call, ToolResult result, ToolExecutionContext context);

    /** before 决策结果。 */
    sealed interface BeforeDecision {
        /** 放行执行。 */
        record Allow() implements BeforeDecision {}

        /** 拒绝执行并携带结构化结果（如 RESTRICTED）。 */
        record Reject(ToolResult result) implements BeforeDecision {}
    }
}
