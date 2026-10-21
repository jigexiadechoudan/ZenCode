package dev.zencode.team;

import dev.zencode.tool.BaseTool;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * TeamSpawn 顶层工具：按角色与配置派生队员（名称注册 + 后端检测 + 可选隔离 worktree
 * + 审批开关），启动执行后端。无当前团队/非法名/后端不可用返回可读错误。
 */
public final class TeamSpawnTool extends BaseTool {

    private final TeamManager manager;

    public TeamSpawnTool(TeamManager manager) {
        super("TeamSpawn",
                "派生一名团队成员：name 队员名，role 角色名（空=通用），task 初始任务，"
                        + "backend tmux/iterm2/inprocess/auto（默认 auto），isolation 是否独立"
                        + "worktree 分支（默认 false），need_approval 是否需 Lead 审批（默认 false）。",
                ToolCategory.COMMAND, ToolPermission.REQUIRES_CONFIRMATION,
                jsonSchema(Map.of(
                        "name", stringProp("队员名（Slug 字符集，团队内唯一）"),
                        "role", stringProp("角色名（内置 explore/plan/general-purpose 或自定义），空=通用"),
                        "task", stringProp("初始任务描述"),
                        "backend", stringProp("tmux / iterm2 / inprocess / auto（默认 auto）"),
                        "isolation", booleanProp("是否独立 worktree+分支（默认 false）"),
                        "need_approval", booleanProp("危险操作是否需 Lead 审批（默认 false）"),
                        "model", stringProp("可选独立模型，空=跟随主模型")
                ), List.of("name", "task")));
        this.manager = manager;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String name = requiredString(args, "name");
        String task = requiredString(args, "task");
        String role = optionalString(args, "role", "");
        BackendType backend = parseBackend(optionalString(args, "backend", "auto"));
        boolean isolation = optionalBoolean(args, "isolation", false);
        boolean needApproval = optionalBoolean(args, "need_approval", false);
        String model = optionalString(args, "model", "");
        try {
            SpawnRequest req = new SpawnRequest(name, role, task, backend, isolation,
                    needApproval, model.isBlank() ? null : model, 0);
            TeamMember member = manager.spawnMember(req);
            return ToolResult.success(ctx.callId(), name(),
                    "队员已派生: " + member.name() + " (agentId=" + member.agentId()
                            + ", 后端=" + member.backend()
                            + (member.isolation() ? ", 隔离" : "")
                            + (member.needApproval() ? ", 需审批" : "") + ")");
        } catch (TeamException e) {
            return ToolResult.error(ctx.callId(), name(), e.getMessage());
        }
    }

    private static BackendType parseBackend(String raw) {
        if (raw == null || raw.isBlank()) return BackendType.AUTO;
        try {
            return BackendType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new TeamException("未知后端: " + raw + "（tmux/iterm2/inprocess/auto）");
        }
    }
}
