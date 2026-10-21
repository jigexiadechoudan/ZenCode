package dev.zencode.command.builtin;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;
import dev.zencode.subagent.TaskManager;
import dev.zencode.subagent.TaskState;

import java.util.List;

/**
 * /tasks：列出所有后台任务（状态 + 概要）。
 */
public final class TasksCommand implements CommandHandler {

    private final TaskManager taskManager;

    public TasksCommand(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public CommandResult handle(CommandContext ctx) {
        List<TaskState> tasks = taskManager.list();
        if (tasks.isEmpty()) {
            return new CommandResult.ShowMessage("无后台任务");
        }
        StringBuilder sb = new StringBuilder("后台任务:");
        for (TaskState t : tasks) {
            sb.append("\n  ").append(shortId(t.id()))
                    .append("  [").append(t.status()).append("]  ")
                    .append(t.description());
        }
        return new CommandResult.ShowMessage(sb.toString());
    }

    private static String shortId(String id) {
        return id == null || id.length() <= 8 ? id : id.substring(0, 8);
    }
}
