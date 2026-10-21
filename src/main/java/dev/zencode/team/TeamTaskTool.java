package dev.zencode.team;

import dev.zencode.tool.BaseTool;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 团队共享任务清单工具（工具名 {@code task}），注入队员工具池：
 * create（创建，带可选依赖）/ view / list / update（状态/认领人/描述）。
 * 绑定所属团队（rt.board），防串用。
 */
public final class TeamTaskTool extends BaseTool {

    private final TeamRuntime rt;

    public TeamTaskTool(TeamRuntime rt) {
        super("task",
                "团队共享任务清单：create 创建任务（可带 dependencies 先决任务 id 逗号分隔），"
                        + "view 查看，list 列举，update 更新状态/认领人/描述。",
                ToolCategory.COMMAND, ToolPermission.REQUIRES_CONFIRMATION,
                jsonSchema(Map.of(
                        "action", stringProp("create / view / list / update"),
                        "title", stringProp("任务标题（create 必填）"),
                        "description", stringProp("任务描述"),
                        "dependencies", stringProp("先决任务 id，逗号分隔（create 可选）"),
                        "task_id", stringProp("任务 id（view / update）"),
                        "status", stringProp("open / in_progress / done / blocked（update 可选）"),
                        "assignee", stringProp("认领队员名（update 可选）")
                ), List.of("action")));
        this.rt = rt;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String action = requiredString(args, "action");
        return switch (action) {
            case "create" -> create(args, ctx);
            case "view" -> view(args, ctx);
            case "list" -> list(ctx);
            case "update" -> update(args, ctx);
            default -> ToolResult.error(ctx.callId(), name(), "未知动作: " + action + "（create/view/list/update）");
        };
    }

    private ToolResult create(Map<String, Object> args, ToolExecutionContext ctx) {
        String title = optionalString(args, "title", "");
        if (title.isBlank()) {
            return ToolResult.error(ctx.callId(), name(), "create 需要 title");
        }
        String description = optionalString(args, "description", "");
        List<String> deps = parseDependencies(optionalString(args, "dependencies", ""));
        String by = optionalString(args, "assignee", "");
        TeamTask task = rt.board().create(title, description, deps, by.isBlank() ? "member" : by);
        return ToolResult.success(ctx.callId(), name(), "任务已创建: " + task.id() + " " + task.title());
    }

    private ToolResult view(Map<String, Object> args, ToolExecutionContext ctx) {
        String id = optionalString(args, "task_id", "");
        Optional<TeamTask> task = rt.board().get(id);
        if (task.isEmpty()) {
            return ToolResult.error(ctx.callId(), name(), "任务不存在: " + id);
        }
        return ToolResult.success(ctx.callId(), name(), format(task.get()));
    }

    private ToolResult list(ToolExecutionContext ctx) {
        List<TeamTask> tasks = rt.board().list();
        if (tasks.isEmpty()) {
            return ToolResult.success(ctx.callId(), name(), "任务清单为空");
        }
        StringBuilder sb = new StringBuilder("任务清单:");
        for (TeamTask task : tasks) {
            sb.append("\n  ").append(task.id()).append(" [").append(task.status()).append("] ")
                    .append(task.title());
            if (task.assignee() != null) {
                sb.append(" → ").append(task.assignee());
            }
            if (!task.dependencies().isEmpty()) {
                sb.append(" 依赖=").append(task.dependencies());
            }
        }
        return ToolResult.success(ctx.callId(), name(), sb.toString());
    }

    private ToolResult update(Map<String, Object> args, ToolExecutionContext ctx) {
        String id = optionalString(args, "task_id", "");
        if (id.isBlank()) {
            return ToolResult.error(ctx.callId(), name(), "update 需要 task_id");
        }
        TeamTaskStatus status = parseStatus(optionalString(args, "status", ""));
        String assignee = optionalString(args, "assignee", "");
        String description = optionalString(args, "description", "");
        try {
            TeamTask updated = rt.board().update(id, status, assignee.isBlank() ? null : assignee,
                    description.isBlank() ? null : description);
            return ToolResult.success(ctx.callId(), name(), "任务已更新: " + format(updated));
        } catch (TeamException e) {
            return ToolResult.error(ctx.callId(), name(), e.getMessage());
        }
    }

    private static String format(TeamTask task) {
        return task.id() + " [" + task.status() + "] " + task.title()
                + (task.assignee() == null ? "" : " → " + task.assignee());
    }

    private static List<String> parseDependencies(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> deps = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) deps.add(trimmed);
        }
        return deps;
    }

    private static TeamTaskStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return TeamTaskStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new TeamException("未知任务状态: " + raw);
        }
    }
}
