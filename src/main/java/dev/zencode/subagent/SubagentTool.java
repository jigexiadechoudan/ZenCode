package dev.zencode.subagent;

import dev.zencode.chat.Conversation;
import dev.zencode.tool.BaseTool;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 统一的子 Agent 委派工具（工具名 {@code agent}）。
 * <p>
 * 按 {@code subagent_type} 分流：省略/空 → Fork（继承父历史，强制后台）；
 * 填内置角色名 → 定义式；填未知角色名 → 返回结构化错误反馈给主 LLM。
 * </p>
 */
public final class SubagentTool extends BaseTool {

    private static final String PARAM_TASK = "task";
    private static final String PARAM_TYPE = "subagent_type";
    private static final String PARAM_BACKGROUND = "background";

    private final AgentLoader loader;
    private final SubagentRunner runner;
    private final TaskManager taskManager;
    private final SubagentContext context;
    private final Conversation parentConversation;

    public SubagentTool(AgentLoader loader, SubagentRunner runner, TaskManager taskManager,
                        SubagentContext context, Conversation parentConversation) {
        super("agent",
                "把子任务委派给独立子 Agent 执行。省略 subagent_type 时走 Fork（继承当前对话历史）；"
                        + "指定内置角色名（explore/plan/general-purpose/review）时走定义式（空白对话+固定角色）。"
                        + "background=true 后台运行。",
                ToolCategory.COMMAND, ToolPermission.REQUIRES_CONFIRMATION,
                jsonSchema(Map.of(
                        PARAM_TASK, stringProp("委派给子 Agent 的任务描述"),
                        PARAM_TYPE, stringProp("子 Agent 角色名；省略或空=Fork 继承父历史"),
                        PARAM_BACKGROUND, booleanProp("是否后台运行（Fork 强制后台）")
                ), List.of(PARAM_TASK)));
        this.loader = loader;
        this.runner = runner;
        this.taskManager = taskManager;
        this.context = context;
        this.parentConversation = parentConversation;
        loader.load();
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String task = requiredString(args, PARAM_TASK);
        String type = optionalString(args, PARAM_TYPE, "");
        boolean background = optionalBoolean(args, PARAM_BACKGROUND, false);

        if (ctx.planOnly() && !("explore".equals(type) || "plan".equals(type))) {
            return ToolResult.restricted(ctx.callId(), name(),
                    "plan-only 只允许显式委派 explore 或 plan 只读角色");
        }

        // Fork 路径：省略/空 subagent_type，继承父历史，强制后台
        if (type.isBlank()) {
            String taskId = taskManager.submitWithRun("Fork 子任务",
                    onRun -> runner.runFork(parentConversation, task, context, onRun));
            return ToolResult.success(ctx.callId(), name(), "已后台启动 Fork 子任务，taskId=" + taskId);
        }

        Optional<AgentDefinition> def = loader.find(type);
        if (def.isEmpty()) {
            return ToolResult.error(ctx.callId(), name(), "未知角色: " + type);
        }

        if (background) {
            String taskId = taskManager.submitWithRun("子任务(" + type + ")",
                    onRun -> runner.runDefinition(def.get(), task, context, onRun, ctx.planOnly()));
            return ToolResult.success(ctx.callId(), name(), "已后台启动子任务，taskId=" + taskId);
        }

        SubagentResult result = runner.runDefinition(def.get(), task, context,
                ignored -> {}, ctx.planOnly());
        return toToolResult(ctx, result);
    }

    private ToolResult toToolResult(ToolExecutionContext ctx, SubagentResult result) {
        if (result.status() == TaskStatus.COMPLETED) {
            return ToolResult.success(ctx.callId(), name(), result.output());
        }
        return ToolResult.error(ctx.callId(), name(),
                "子任务" + result.status() + ": " + result.output());
    }
}
