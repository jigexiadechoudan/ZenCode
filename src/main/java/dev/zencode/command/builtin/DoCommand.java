package dev.zencode.command.builtin;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;

/**
 * /do：把参数作为任务提示词提交给 AI 执行；无参数时显示用法。
 */
public final class DoCommand implements CommandHandler {

    @Override
    public CommandResult handle(CommandContext ctx) {
        String args = ctx.args();
        if (args.isEmpty()) {
            return new CommandResult.ShowMessage("用法: /do <任务描述>，把任务交给 AI 执行。");
        }
        boolean submitted = ctx.ui().submit(args);
        if (submitted) {
            return new CommandResult.Submission(args);
        }
        return new CommandResult.ShowMessage("提交失败（可能忙碌中）");
    }
}