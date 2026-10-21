package dev.zencode.hook;

import java.time.Duration;

/** command 动作：通过系统 shell 执行命令，支持超时。 */
public record CommandAction(String command, Duration timeout) implements Action {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    public CommandAction {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command 动作缺少 command");
        }
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }
}
