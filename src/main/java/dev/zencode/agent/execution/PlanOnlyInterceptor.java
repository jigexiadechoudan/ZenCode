package dev.zencode.agent.execution;

import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;

import java.util.function.Function;

/**
 * plan-only 模式的 before 拦截器：只放行 {@link ToolCategory#READ} 工具，
 * 对写工具和命令工具返回关联原调用 ID 的 {@code RESTRICTED} 结果且不执行。
 */
public final class PlanOnlyInterceptor implements ToolExecutionInterceptor {

    private final Function<String, ToolCategory> categoryLookup;

    /** 使用工具注册中心解析工具分类。 */
    public PlanOnlyInterceptor(ToolRegistry registry) {
        this.categoryLookup = name -> registry.find(name).map(tool -> tool.category()).orElse(null);
    }

    /** 使用自定义分类解析器，便于测试。 */
    public PlanOnlyInterceptor(Function<String, ToolCategory> categoryLookup) {
        this.categoryLookup = categoryLookup;
    }

    @Override
    public BeforeDecision before(ToolCall call, ToolExecutionContext context) {
        ToolCategory category = categoryLookup.apply(call.toolName());
        boolean readOnlyDelegate = "agent".equalsIgnoreCase(call.toolName())
                && ("explore".equals(call.arguments().get("subagent_type"))
                || "plan".equals(call.arguments().get("subagent_type")));
        if (category != ToolCategory.READ && !readOnlyDelegate) {
            return new BeforeDecision.Reject(ToolResult.restricted(
                    call.callId(), call.toolName(),
                    "plan-only 模式禁止执行该工具，退出 plan-only 后再执行"));
        }
        return new BeforeDecision.Allow();
    }

    @Override
    public ToolResult after(ToolCall call, ToolResult result, ToolExecutionContext context) {
        return result;
    }
}
