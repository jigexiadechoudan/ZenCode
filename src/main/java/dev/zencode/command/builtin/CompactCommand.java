package dev.zencode.command.builtin;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;
import dev.zencode.context.ContextCompressor;

/**
 * /compact：手动触发上下文压缩，展示前后 Token 变化。
 */
public final class CompactCommand implements CommandHandler {

    @Override
    public CommandResult handle(CommandContext ctx) {
        ContextCompressor.CompactResult result = ctx.ui().compact();
        if (result.ok()) {
            long saved = result.beforeTokens() - result.afterTokens();
            return new CommandResult.ShowMessage("上下文压缩完成: " + result.beforeTokens()
                    + " → " + result.afterTokens() + " tokens（节省 " + saved + "）");
        }
        return new CommandResult.ShowMessage("压缩未执行: " + result.message());
    }
}