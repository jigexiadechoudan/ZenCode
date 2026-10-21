package dev.zencode.command.builtin;

import dev.zencode.command.CommandRegistry;
import dev.zencode.command.CommandSpec;
import dev.zencode.command.CommandMode;

import java.util.List;

/**
 * 注册全部内置命令的工厂。返回配置好的 {@link CommandRegistry}。
 */
public final class BuiltInCommands {

    private BuiltInCommands() {}

    /** 创建并注册十个内置命令的注册中心。 */
    public static CommandRegistry build() {
        CommandRegistry registry = new CommandRegistry();

        registry.register(new CommandSpec("/permission",
                List.of("/perm"), "切换权限模式",
                "/permission [locked|read_only|default|lenient|trust_all|plan]",
                "切换权限模式，缺省打开选择", CommandMode.UI, false,
                new PermissionCommand()));
        registry.register(new CommandSpec("/plan", List.of(),
                "切换计划模式（开/关）", "/plan [on|off]",
                "on 打开 / off 关闭 / 缺省切换", CommandMode.UI, false,
                new PlanCommand()));
        registry.register(new CommandSpec("/compact", List.of("/c"),
                "压缩上下文（释放 Token）", "/compact", "",
                CommandMode.LOCAL, false, new CompactCommand()));
        registry.register(new CommandSpec("/clear", List.of(),
                "清空对话视图历史", "/clear", "", CommandMode.UI, false,
                new ClearCommand()));
        registry.register(new CommandSpec("/do", List.of(),
                "把参数作为任务提交给 AI", "/do <任务>", "任务描述", CommandMode.SUBMIT, false,
                new DoCommand()));
        registry.register(new CommandSpec("/status", List.of("/st"),
                "展示综合状态", "/status", "", CommandMode.LOCAL, false,
                new StatusCommand()));
        registry.register(new CommandSpec("/session", List.of(),
                "会话管理", "/session list|load|delete|new",
                "list / load <id> / delete <id> / new", CommandMode.LOCAL, false,
                new SessionCommand()));
        registry.register(new CommandSpec("/memory", List.of(),
                "记忆管理", "/memory show|clear|open|extract",
                "show / clear <scope> / open <scope> / extract", CommandMode.LOCAL, false,
                new MemoryCommand()));
        registry.register(new CommandSpec("/help", List.of("/h", "/?"),
                "显示帮助", "/help [命令]", "", CommandMode.LOCAL, false,
                new HelpCommand(registry)));

        return registry;
    }
}