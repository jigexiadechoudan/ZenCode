package dev.zencode.command;

/**
 * 命令处理逻辑接口。实现从 {@link CommandContext} 读取参数、调用
 * 应用能力，返回 {@link CommandResult} 交由上层解码。
 */
@FunctionalInterface
public interface CommandHandler {
    CommandResult handle(CommandContext ctx);
}