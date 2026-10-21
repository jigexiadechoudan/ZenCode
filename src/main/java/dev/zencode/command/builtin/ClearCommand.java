package dev.zencode.command.builtin;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;

/**
 * /clear：清空终端对话视图的滚动历史（不改变 AI 对话上下文）。
 */
public final class ClearCommand implements CommandHandler {

    @Override
    public CommandResult handle(CommandContext ctx) {
        ctx.ui().clearActiveSkills();
        return new CommandResult.UiAction(CommandResult.UiActionKind.CLEAR_TRANSCRIPT);
    }
}