package dev.zencode.team;

import dev.zencode.tool.BaseTool;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * TeamTerminate 顶层工具：终止当前团队某队员（in-process 取消 run；外部进程杀进程/关窗格）。
 */
public final class TeamTerminateTool extends BaseTool {

    private final TeamManager manager;

    public TeamTerminateTool(TeamManager manager) {
        super("TeamTerminate",
                "终止团队成员：停止其运行并标记 TERMINATED。",
                ToolCategory.COMMAND, ToolPermission.REQUIRES_CONFIRMATION,
                jsonSchema(Map.of(
                        "name", stringProp("队员名")
                ), List.of("name")));
        this.manager = manager;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String name = requiredString(args, "name");
        try {
            boolean terminated = manager.terminateMember(name);
            return ToolResult.success(ctx.callId(), name(),
                    terminated ? "队员已终止: " + name : "队员已是终止状态: " + name);
        } catch (TeamException e) {
            return ToolResult.error(ctx.callId(), name(), e.getMessage());
        }
    }
}
