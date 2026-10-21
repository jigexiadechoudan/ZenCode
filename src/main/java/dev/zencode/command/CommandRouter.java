package dev.zencode.command;

import dev.zencode.hook.HookContext;
import dev.zencode.hook.HookEngine;
import dev.zencode.hook.HookEvent;

import java.util.Map;

/**
 * 命令分发器：解析输入 → 查找命令 → 执行 → 返回结果。
 * <p>
 * 不直接修改 UI，仅返回 {@link CommandResult} 由上层（渲染层）解码。
 * 未知命令统一返回引导到 {@code /help} 的消息；非 {@code /} 输入返回 null。
 * </p>
 */
public final class CommandRouter {

    private final CommandRegistry registry;
    private final HookEngine hookEngine;

    public CommandRouter(CommandRegistry registry) {
        this(registry, null);
    }

    /** 可选注入 Hook 引擎：解析到命令后触发 command_execute 事件。 */
    public CommandRouter(CommandRegistry registry, HookEngine hookEngine) {
        this.registry = registry;
        this.hookEngine = hookEngine;
    }

    /** 返回底层注册中心（供菜单补全等复用同一实例）。 */
    public CommandRegistry registry() {
        return registry;
    }

    /**
     * 分发命令。
     *
     * @param input 用户输入
     * @param ui    应用能力
     * @return 执行结果；非命令输入返回 null
     */
    public CommandResult dispatch(String input, UiController ui) {
        ParsedCommand parsed = CommandParser.parse(input);
        if (parsed == null) {
            return null;
        }
        CommandSpec spec = registry.find(parsed.name()).orElse(null);
        if (spec == null) {
            return new CommandResult.ShowMessage("未知命令: /" + parsed.name() + "。使用 /help 查看可用命令。");
        }
        if (hookEngine != null) {
            String message = "/" + parsed.name()
                    + (parsed.args() == null || parsed.args().isBlank() ? "" : " " + parsed.args());
            hookEngine.runHooks(HookEvent.COMMAND_EXECUTE, new HookContext(
                    HookEvent.COMMAND_EXECUTE.name().toLowerCase(java.util.Locale.ROOT),
                    null, Map.of(), null, message, null));
        }
        CommandContext ctx = new CommandContext(ui, parsed.name(), parsed.args());
        return spec.handler().handle(ctx);
    }
}