package dev.zencode.team;

import dev.zencode.tool.BaseTool;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * TeamMerge 顶层工具：收敛合并——把 isolation 队员的独立分支合并回主分支；
 * 冲突时回滚本次合并并报告需要人工解决的分支。
 */
public final class TeamMergeTool extends BaseTool {

    private final TeamManager manager;

    public TeamMergeTool(TeamManager manager) {
        super("TeamMerge",
                "收敛合并：把隔离队员（独立 worktree+分支）的产物合并回主分支，"
                        + "出现冲突时回滚本次合并，并报告需要人工解决的分支。",
                ToolCategory.COMMAND, ToolPermission.REQUIRES_CONFIRMATION,
                jsonSchema(Map.<String, Map<String, Object>>of(), List.of()));
        this.manager = manager;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        try {
            MergeResult result = manager.mergeTeam();
            if (result.success()) {
                return ToolResult.success(ctx.callId(), name(), result.message());
            }
            return ToolResult.error(ctx.callId(), name(), result.message());
        } catch (TeamException e) {
            return ToolResult.error(ctx.callId(), name(), e.getMessage());
        }
    }
}
