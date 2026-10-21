package dev.zencode.command.builtin;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;

/**
 * /plan：切换计划模式。支持 on/off 参数；无参数时切换当前状态。
 */
public final class PlanCommand implements CommandHandler {

    @Override
    public CommandResult handle(CommandContext ctx) {
        String args = ctx.args();
        if (args.equalsIgnoreCase("on")) {
            ctx.ui().setPlanOnly(true);
            return new CommandResult.UiAction(CommandResult.UiActionKind.PLAN_SWITCHED);
        }
        if (args.equalsIgnoreCase("off")) {
            ctx.ui().setPlanOnly(false);
            return new CommandResult.UiAction(CommandResult.UiActionKind.PLAN_SWITCHED);
        }
        if (args.isEmpty()) {
            ctx.ui().setPlanOnly(!ctx.ui().planOnly());
            return new CommandResult.UiAction(CommandResult.UiActionKind.PLAN_SWITCHED);
        }
        return new CommandResult.ShowMessage("用法: /plan [on|off]（缺省切换当前计划模式）");
    }
}