package dev.zencode.command.builtin;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;

/**
 * /memory：记忆管理。参数 show / clear &lt;scope> / open &lt;scope> / extract。
 */
public final class MemoryCommand implements CommandHandler {

    @Override
    public CommandResult handle(CommandContext ctx) {
        String args = ctx.args();
        String sub = firstToken(args).toLowerCase();
        switch (sub) {
            case "" -> {
                return new CommandResult.ShowMessage(
                        "用法: /memory show|clear|open|extract");
            }
            case "show" -> {
                return new CommandResult.ShowMessage("记忆内容:\n" + ctx.ui().memoryRead("MERGED"));
            }
            case "clear" -> {
                String scope = secondToken(args);
                boolean ok = ctx.ui().memoryClear(scope);
                return ok ? new CommandResult.ShowMessage("已清空记忆: " + scope)
                        : new CommandResult.ShowMessage("清空失败（作用域无效或记忆未启用）");
            }
            case "open" -> {
                String scope = secondToken(args);
                String path = ctx.ui().memoryFile(scope);
                return path.isEmpty()
                        ? new CommandResult.ShowMessage("记忆未启用或无该作用域")
                        : new CommandResult.ShowMessage("记忆文件: " + path);
            }
case "extract" -> {
                boolean ok = ctx.ui().memoryExtract();
                return ok ? new CommandResult.ShowMessage("记忆提取已触发")
                        : new CommandResult.ShowMessage("记忆提取不可用（系统未启用或忙碌中）");
            }
            default -> {
                return new CommandResult.ShowMessage("未知子命令: " + sub + "。用法见 /help memory");
            }
        }
    }

    private static String firstToken(String args) {
        int space = args.indexOf(' ');
        return space < 0 ? args : args.substring(0, space);
    }

    private static String secondToken(String args) {
        int space = args.indexOf(' ');
        if (space < 0) return "PROJECT";
        String rest = args.substring(space + 1).trim();
        if (rest.isEmpty()) return "PROJECT";
        return rest;
    }
}