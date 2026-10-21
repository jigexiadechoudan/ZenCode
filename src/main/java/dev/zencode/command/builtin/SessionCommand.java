package dev.zencode.command.builtin;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;
import dev.zencode.session.SessionMeta;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * /session：会话管理。参数 list / load &lt;id> / delete &lt;id> / new。
 */
public final class SessionCommand implements CommandHandler {

    @Override
    public CommandResult handle(CommandContext ctx) {
        String args = ctx.args();
        String sub = firstToken(args).toLowerCase();
        switch (sub) {
            case "" -> {
                return new CommandResult.ShowMessage(
                        "用法: /session list|load <id>|delete <id>|new");
            }
            case "list" -> {
                List<SessionMeta> sessions = ctx.ui().listSessions();
                if (sessions.isEmpty()) {
                    return new CommandResult.ShowMessage("暂无会话记录");
                }
                StringBuilder sb = new StringBuilder("会话列表:");
                for (SessionMeta meta : sessions) {
                    sb.append("\n  ").append(shortId(meta.id()))
                            .append("  ").append(meta.title().isEmpty() ? "(无标题)" : meta.title())
                            .append("  ").append(meta.messageCount()).append("条")
                            .append("  ").append(new SimpleDateFormat("MM-dd HH:mm")
                                    .format(new Date(meta.lastActiveMs())));
                }
                return new CommandResult.ShowMessage(sb.toString());
            }
            case "load" -> {
                String id = argAfter(args, "load");
                if (id.isEmpty()) {
                    return new CommandResult.ShowMessage("用法: /session load <会话ID>");
                }
                return ctx.ui().restoreSession(id)
                        ? new CommandResult.ShowMessage("已恢复会话 " + shortId(id))
                        : new CommandResult.ShowMessage("恢复会话失败（忙碌或 ID 无效）");
            }
            case "delete" -> {
                String id = argAfter(args, "delete");
                if (id.isEmpty()) {
                    return new CommandResult.ShowMessage("用法: /session delete <会话ID>");
                }
                return ctx.ui().deleteSession(id)
                        ? new CommandResult.ShowMessage("已删除会话 " + shortId(id))
                        : new CommandResult.ShowMessage("删除会话失败（不能删除当前活跃会话或 ID 无效）");
            }
            case "new" -> {
                boolean ok = ctx.ui().newSession();
                return ok ? new CommandResult.ShowMessage("已新建会话")
                        : new CommandResult.ShowMessage("新建会话失败（忙碌中）");
            }
            default -> {
                return new CommandResult.ShowMessage("未知子命令: " + sub + "。用法见 /help session");
            }
        }
    }

    private static String firstToken(String args) {
        int space = args.indexOf(' ');
        return space < 0 ? args : args.substring(0, space);
    }

    private static String argAfter(String args, String sub) {
        String rest = args.substring(Math.min(sub.length(), args.length())).trim();
        if (rest.startsWith("/")) rest = rest.substring(1).trim();
        return rest;
    }

    private static String shortId(String id) {
        return id.substring(0, Math.min(8, id.length())) + "…";
    }
}