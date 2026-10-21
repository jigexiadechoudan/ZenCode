package dev.zencode.command.builtin;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;
import dev.zencode.subagent.TaskManager;
import dev.zencode.subagent.TaskState;

import java.util.Optional;

/**
 * /task info &lt;id&gt; 查看任务详情；/task cancel &lt;id&gt; 终止任务。
 */
public final class TaskCommand implements CommandHandler {

    private final TaskManager taskManager;

    public TaskCommand(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public CommandResult handle(CommandContext ctx) {
        String args = ctx.args() == null ? "" : ctx.args().trim();
        if (args.startsWith("info")) {
            return info(args.substring("info".length()).trim());
        }
        if (args.startsWith("cancel")) {
            return cancel(args.substring("cancel".length()).trim());
        }
        return new CommandResult.ShowMessage("用法: /task info <id> 或 /task cancel <id>");
    }

    private CommandResult info(String id) {
        if (id.isBlank()) {
            return new CommandResult.ShowMessage("用法: /task info <id>");
        }
        Optional<TaskState> found = taskManager.find(id);
        if (found.isEmpty()) {
            return new CommandResult.ShowMessage("任务不存在: " + id);
        }
        TaskState t = found.get();
        StringBuilder sb = new StringBuilder("任务详情:");
        sb.append("\n  id: ").append(t.id());
        sb.append("\n  描述: ").append(t.description());
        sb.append("\n  状态: ").append(t.status());
        sb.append("\n  开始: ").append(t.startTime());
        sb.append("\n  结束: ").append(t.endTime() == null ? "（运行中）" : t.endTime().toString());
        sb.append("\n  token: ").append(usageSummary(t.usage()));
        if (!t.result().isBlank()) {
            sb.append("\n  结果: ").append(t.result());
        }
        return new CommandResult.ShowMessage(sb.toString());
    }

    private CommandResult cancel(String id) {
        if (id.isBlank()) {
            return new CommandResult.ShowMessage("用法: /task cancel <id>");
        }
        boolean ok = taskManager.cancel(id);
        return new CommandResult.ShowMessage(ok ? "已取消任务: " + id : "任务不存在或已终止: " + id);
    }

    private static String usageSummary(dev.zencode.chat.Usage usage) {
        if (usage == null || usage.totalTokens() == null) return "未知";
        return "输入 " + n(usage.inputTokens()) + " / 输出 " + n(usage.outputTokens())
                + " / 合计 " + usage.totalTokens();
    }

    private static String n(Integer v) {
        return v == null ? "0" : v.toString();
    }
}
