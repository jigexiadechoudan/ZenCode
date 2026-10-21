package dev.zencode.command;

import java.util.List;
import java.util.Objects;

/**
 * 一条命令的静态元数据。
 * <p>
 * 命令名与别名均以 {@code /} 开头且至少含一个字符。命令名是规范名，
 * 别名用于快捷输入与补全；hidden 命令不参与列表展示但可被查找。
 * </p>
 */
public record CommandSpec(
        String command,
        List<String> aliases,
        String description,
        String usage,
        String argumentTip,
        CommandMode mode,
        boolean hidden,
        CommandHandler handler) {

    public CommandSpec {
        if (command == null || !command.startsWith("/") || command.length() < 2) {
            throw new IllegalArgumentException("命令名必须以 / 开头且至少含一个字符: " + command);
        }
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        for (String alias : aliases) {
            if (alias == null || !alias.startsWith("/") || alias.length() < 2) {
                throw new IllegalArgumentException("别名必须以 / 开头且至少含一个字符: " + alias);
            }
        }
        description = Objects.requireNonNullElse(description, "");
        usage = Objects.requireNonNullElse(usage, command);
        argumentTip = Objects.requireNonNullElse(argumentTip, "");
        mode = Objects.requireNonNullElse(mode, CommandMode.LOCAL);
        handler = Objects.requireNonNull(handler, "命令处理逻辑不能为空");
    }

    /** 便捷构造：无别名、非隐藏。 */
    public static CommandSpec of(String command, String description, CommandMode mode,
                                 CommandHandler handler) {
        return new CommandSpec(command, List.of(), description, command, "", mode, false, handler);
    }
}