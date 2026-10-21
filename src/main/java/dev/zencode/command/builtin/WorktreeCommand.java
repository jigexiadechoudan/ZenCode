package dev.zencode.command.builtin;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;
import dev.zencode.worktree.WorktreeInfo;
import dev.zencode.worktree.WorktreeManager;
import dev.zencode.worktree.WorktreeOperationResult;

import java.util.List;

/**
 * /worktree：手动管理 worktree，含 list / create / enter / exit / remove 五个子命令。
 */
public final class WorktreeCommand implements CommandHandler {

    private final WorktreeManager manager;

    public WorktreeCommand(WorktreeManager manager) {
        this.manager = manager;
    }

    @Override
    public CommandResult handle(CommandContext ctx) {
        String args = ctx.args() == null ? "" : ctx.args().trim();
        if (args.isBlank()) {
            return show(usage());
        }
        String[] parts = args.split("\\s+", 2);
        String sub = parts[0];
        String rest = parts.length > 1 ? parts[1].trim() : "";
        return switch (sub) {
            case "list" -> show(list());
            case "create" -> show(create(rest));
            case "enter" -> show(enter(rest));
            case "exit" -> show(exit());
            case "remove" -> show(remove(rest));
            default -> show(usage());
        };
    }

    private String list() {
        List<WorktreeInfo> worktrees = manager.list();
        if (worktrees.isEmpty()) {
            return "无 worktree";
        }
        StringBuilder sb = new StringBuilder("Worktree 列表:");
        for (WorktreeInfo w : worktrees) {
            sb.append("\n  ").append(w.name()).append("  [").append(w.branch()).append("]");
            if (w.name().equals(manager.currentName())) {
                sb.append("  ←当前");
            }
        }
        return sb.toString();
    }

    private String create(String name) {
        if (name.isBlank()) return "用法: /worktree create <name>";
        WorktreeOperationResult r = manager.create(name);
        return r.message();
    }

    private String enter(String name) {
        if (name.isBlank()) return "用法: /worktree enter <name>";
        WorktreeOperationResult r = manager.enter(name);
        return r.message();
    }

    private String exit() {
        return manager.exit().message();
    }

    private String remove(String arg) {
        if (arg.isBlank()) return "用法: /worktree remove <name> [--discard]";
        boolean discard = arg.contains("--discard");
        String name = arg.replace("--discard", "").trim();
        if (name.isBlank()) return "用法: /worktree remove <name> [--discard]";
        WorktreeOperationResult r = manager.remove(name, discard);
        return r.message();
    }

    private static String usage() {
        return "用法: /worktree list | create <name> | enter <name> | exit | remove <name> [--discard]";
    }

    private static CommandResult show(String text) {
        return new CommandResult.ShowMessage(text);
    }
}
