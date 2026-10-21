package dev.zencode.hook;

import dev.zencode.agent.execution.ToolExecutionInterceptor;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolResult;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Hook 拦截器适配：把 pre_tool_use/post_tool_use/file_change 事件接入工具拦截链。
 * <p>before 拒绝转换为受限工具结果反馈给 LLM；after 触发工具后事件并原样返回结果。</p>
 */
public final class HookToolInterceptor implements ToolExecutionInterceptor {

    private final HookEngine engine;

    public HookToolInterceptor(HookEngine engine) {
        this.engine = Objects.requireNonNull(engine, "HookEngine 不能为空");
    }

    @Override
    public BeforeDecision before(ToolCall call, ToolExecutionContext context) {
        Optional<ToolRejectedError> rejected = engine.runPreToolHooks(call, context);
        if (rejected.isPresent()) {
            ToolRejectedError error = rejected.get();
            return new BeforeDecision.Reject(ToolResult.restricted(
                    error.callId() == null ? call.callId() : error.callId(),
                    error.toolName() == null ? call.toolName() : error.toolName(),
                    error.reason()));
        }
        return new BeforeDecision.Allow();
    }

    @Override
    public ToolResult after(ToolCall call, ToolResult result, ToolExecutionContext context) {
        Map<String, Object> args = call.arguments();
        String filePath = pathOf(args);
        engine.runHooks(HookEvent.POST_TOOL_USE, new HookContext(
                HookEvent.POST_TOOL_USE.name().toLowerCase(),
                call.toolName(), args, filePath, null,
                result.success() ? null : result.content()));
        if (isFileWriteTool(call.toolName()) && result.success()) {
            engine.runHooks(HookEvent.FILE_CHANGE, new HookContext(
                    HookEvent.FILE_CHANGE.name().toLowerCase(),
                    call.toolName(), args, filePath, null, null));
        }
        return result;
    }

    private static boolean isFileWriteTool(String toolName) {
        return "WriteFile".equals(toolName) || "EditFile".equals(toolName);
    }

    private static String pathOf(Map<String, Object> args) {
        Object path = args.get("path");
        return path == null ? null : String.valueOf(path);
    }
}
