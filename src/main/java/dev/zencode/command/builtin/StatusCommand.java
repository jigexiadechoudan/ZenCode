package dev.zencode.command.builtin;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;
import dev.zencode.permission.PermissionMode;

/**
 * /status：展示综合状态（权限模式、计划模式、上下文 Token 用量）。
 */
public final class StatusCommand implements CommandHandler {

    @Override
    public CommandResult handle(CommandContext ctx) {
        StringBuilder sb = new StringBuilder("综合状态:");
        sb.append("\n  权限模式: ").append(modeLabel(ctx.ui().permissionMode()));
        sb.append("\n  计划模式: ").append(ctx.ui().planOnly() ? "开" : "关");
        sb.append("\n  上下文: ").append(ctx.ui().estimatedContextTokens())
                .append(" / ").append(ctx.ui().contextWindowTokens()).append(" tokens");
        String sessionId = ctx.ui().currentSessionId();
        if (sessionId != null) {
            sb.append("\n  当前会话: ").append(sessionId.substring(0, Math.min(8, sessionId.length()))).append("…");
        }
        return new CommandResult.ShowMessage(sb.toString());
    }

    private static String modeLabel(PermissionMode mode) {
        return switch (mode) {
            case LOCKED -> "锁定";
            case READ_ONLY -> "只读";
            case DEFAULT -> "默认";
            case LENIENT -> "宽松";
            case TRUST_ALL -> "完全信任";
            case PLAN -> "计划";
        };
    }
}