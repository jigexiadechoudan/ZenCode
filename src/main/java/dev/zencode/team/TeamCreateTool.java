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
 * TeamCreate 顶层工具：创建团队（Lead = 主入口），创建成功后把 SendMessageTool
 * （绑定新团队，from=lead）注册进主注册中心供 Lead 协调。非法名/重名返回可读错误。
 */
public final class TeamCreateTool extends BaseTool {

    private final TeamManager manager;
    private final ToolRegistry registry;

    public TeamCreateTool(TeamManager manager, ToolRegistry registry) {
        super("TeamCreate",
                "创建 Agent 团队：你是 Lead，可随后用 TeamSpawn 派生队员、SendMessage 协调、"
                        + "TeamMerge 收敛、TeamDelete 删除。创建后本工具集（SendMessage 等）对 Lead 可用。",
                ToolCategory.COMMAND, ToolPermission.REQUIRES_CONFIRMATION,
                jsonSchema(Map.of(
                        "name", stringProp("团队名（Slug 字符集，团队内唯一）")
                ), List.of("name")));
        this.manager = manager;
        this.registry = registry;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String name = requiredString(args, "name");
        try {
            Team team = manager.createTeam(name);
            // Lead 协调能力：注册 SendMessage（绑定当前团队）
            manager.currentTeam().ifPresent(rt -> registry.register(
                    new SendMessageTool(rt, manager, "lead")));
            return ToolResult.success(ctx.callId(), name(),
                    "团队已创建: " + team.name() + "（你是 Lead）");
        } catch (TeamException e) {
            return ToolResult.error(ctx.callId(), name(), e.getMessage());
        }
    }
}
