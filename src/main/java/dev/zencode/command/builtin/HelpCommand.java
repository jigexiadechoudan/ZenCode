package dev.zencode.command.builtin;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandRegistry;
import dev.zencode.command.CommandResult;
import dev.zencode.command.CommandSpec;

/**
 * /help：列出全部可用命令及用法；带参数时显示指定命令详情。
 */
public final class HelpCommand implements CommandHandler {

    private final CommandRegistry registry;

    public HelpCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public CommandResult handle(CommandContext ctx) {
        String args = ctx.args();
        if (args.isEmpty()) {
            StringBuilder sb = new StringBuilder("可用命令:");
            for (CommandSpec spec : registry.list()) {
                sb.append("\n  ").append(spec.command())
                        .append("  ").append(spec.description())
                        .append(spec.usage().isEmpty() ? "" : "  (" + spec.usage() + ")");
            }
            sb.append("\n输入 /help <命令> 查看某命令详细用法");
            return new CommandResult.ShowMessage(sb.toString());
        }
        String target = args.startsWith("/") ? args.substring(1).trim()
                : args.trim();
        CommandSpec spec = registry.find(target).orElse(null);
        if (spec == null) {
            return new CommandResult.ShowMessage("未知命令: " + args + "。使用 /help 查看全部。");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(spec.command()).append(" — ").append(spec.description());
        if (!spec.usage().isEmpty()) sb.append("\n用法: ").append(spec.usage());
        return new CommandResult.ShowMessage(sb.toString());
    }
}