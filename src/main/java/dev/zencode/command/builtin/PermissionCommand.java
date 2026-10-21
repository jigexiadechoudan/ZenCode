package dev.zencode.command.builtin;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;
import dev.zencode.permission.PermissionMode;

/**
 * /permission：切换权限模式。带模式参数直接切换；无参数打开选择入口。
 */
public final class PermissionCommand implements CommandHandler {

    @Override
    public CommandResult handle(CommandContext ctx) {
        String args = ctx.args();
        if (args.isEmpty()) {
            return new CommandResult.UiAction(CommandResult.UiActionKind.OPEN_PERMISSION_PICKER);
        }
        try {
            PermissionMode mode = PermissionMode.fromString(args);
            ctx.ui().switchPermissionMode(mode);
            return new CommandResult.UiAction(CommandResult.UiActionKind.PERMISSION_SWITCHED);
        } catch (IllegalArgumentException e) {
            return new CommandResult.ShowMessage("未知权限模式: " + args
                    + "。可选: locked/read_only/default/lenient/trust_all/plan");
        }
    }
}