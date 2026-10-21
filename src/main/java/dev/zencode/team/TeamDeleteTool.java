package dev.zencode.team;

import dev.zencode.tool.BaseTool;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * TeamDelete 顶层工具：删除团队——终止全部队员、清理隔离 worktree、删除配置与邮箱，
 * 并从主注册中心注销 Lead 的 SendMessage 工具。
 */
public final class TeamDeleteTool extends BaseTool {

    private final TeamManager manager;
    private final ToolRegistry registry;

    public TeamDeleteTool(TeamManager manager, ToolRegistry registry) {
        super("TeamDelete",
                "删除团队：终止全部队员、清理隔离目录与配置。",
                ToolCategory.COMMAND, ToolPermission.REQUIRES_CONFIRMATION,
                jsonSchema(Map.<String, Map<String, Object>>of(), List.of()));
        this.manager = manager;
        this.registry = registry;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String name = manager.currentTeamName();
        if (name == null) {
            return ToolResult.error(ctx.callId(), name(), "当前没有团队");
        }
        try {
            boolean deleted = manager.deleteTeam(name);
            if (deleted) {
                if (registry.find("send_message").isPresent()) {
                    registry.unregister("send_message");
                }
                return ToolResult.success(ctx.callId(), name(), "团队已删除: " + name);
            }
            return ToolResult.error(ctx.callId(), name(), "团队不存在: " + name);
        } catch (TeamException e) {
            return ToolResult.error(ctx.callId(), name(), e.getMessage());
        }
    }
}
