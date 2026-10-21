package dev.zencode.hook;

import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolExecutionContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Hook 引擎核心：执行非拦截事件（runHooks）与 pre_tool_use 拦截（runPreToolHooks）。
 * <p>错误隔离：Hook 执行失败只记日志，绝不中断主流程；reject 是 Hook 有意的安全决策，
 * 走结构化结果反馈给调用方。once 为进程内运行时标记，重启即重置。</p>
 */
public final class HookEngine {

    private static final Logger LOG = Logger.getLogger(HookEngine.class.getName());

    private volatile List<Hook> hooks = List.of();
    private final Set<String> executedOnce = ConcurrentHashMap.newKeySet();
    private final Map<String, String> injectedPrompts = new ConcurrentHashMap<>();

    private final CommandExecutor commandExecutor = new CommandExecutor();
    private final PromptExecutor promptExecutor = new PromptExecutor();
    private final HttpExecutor httpExecutor = new HttpExecutor();
    private final AgentExecutor agentExecutor = new AgentExecutor();

    public void setHooks(List<Hook> hooks) {
        this.hooks = hooks == null ? List.of() : List.copyOf(hooks);
    }

    /** 清空 once 标记与注入缓存（测试辅助）。 */
    public void reset() {
        executedOnce.clear();
        injectedPrompts.clear();
    }

    /** 会话期已注入的 prompt 文本（去重），供 RequestAssembler 每轮注入。 */
    public List<String> injectedPrompts() {
        return List.copyOf(injectedPrompts.values());
    }

    /** 处理非拦截事件：按声明顺序过滤并执行，失败只记日志。 */
    public void runHooks(HookEvent event, HookContext context) {
        List<Hook> snapshot = hooks;
        for (int i = 0; i < snapshot.size(); i++) {
            Hook hook = snapshot.get(i);
            if (hook.event() != event) continue;
            if (!matches(hook, i, context)) continue;
            if (hook.once() && !executedOnce.add(onceKey(hook, i))) continue;
            if (hook.async()) {
                final Hook current = hook;
                final int index = i;
                Thread.ofVirtual().name("zencode-hook-async").start(() -> safeExecute(current, index, context));
            } else {
                safeExecute(hook, i, context);
            }
        }
    }

    /**
     * pre_tool_use 专用入口：同步返回拒绝结果或放行。
     * <p>按声明顺序执行；命中 reject 时立即短路返回拒绝原因，后续 Hook 不再运行。</p>
     */
    public Optional<ToolRejectedError> runPreToolHooks(ToolCall call, ToolExecutionContext context) {
        HookContext hookContext = toolContext(call, context);
        List<Hook> snapshot = hooks;
        for (int i = 0; i < snapshot.size(); i++) {
            Hook hook = snapshot.get(i);
            if (hook.event() != HookEvent.PRE_TOOL_USE) continue;
            if (!matches(hook, i, hookContext)) continue;
            if (hook.once() && !executedOnce.add(onceKey(hook, i))) continue;
            String output;
            try {
                output = executeAction(hook, i, hookContext);
            } catch (Throwable t) {
                final Hook current = hook;
                final int index = i;
                LOG.warning(() -> "Hook 执行失败: " + describe(current, index) + " - " + t);
                output = null;
            }
            if (hook.reject()) {
                String reason = (output == null || output.isBlank()) ? "被 Hook 规则拒绝" : output;
                return Optional.of(new ToolRejectedError(call.callId(), call.toolName(), reason));
            }
        }
        return Optional.empty();
    }

    private boolean matches(Hook hook, int index, HookContext context) {
        if (hook.condition() == null) {
            return true;
        }
        try {
            return hook.condition().matches(context);
        } catch (RuntimeException e) {
            LOG.warning(() -> "Hook 条件求值失败: " + describe(hook, index) + " - " + e.getMessage());
            return false;
        }
    }

    private void safeExecute(Hook hook, int index, HookContext context) {
        try {
            executeAction(hook, index, context);
        } catch (Throwable t) {
            LOG.warning(() -> "Hook 执行失败: " + describe(hook, index) + " - " + t);
        }
    }

    private String executeAction(Hook hook, int index, HookContext context) {
        String output = switch (hook.action()) {
            case CommandAction cmd -> commandExecutor.execute(cmd, context);
            case PromptAction prompt -> promptExecutor.execute(prompt, context);
            case HttpAction http -> httpExecutor.execute(http, context);
            case AgentAction agent -> agentExecutor.execute(agent, context);
        };
        if (!hook.reject() && hook.action() instanceof PromptAction && output != null && !output.isEmpty()) {
            injectedPrompts.putIfAbsent(output, output);
        }
        return output;
    }

    private static HookContext toolContext(ToolCall call, ToolExecutionContext context) {
        return new HookContext(HookEvent.PRE_TOOL_USE.name().toLowerCase(),
                call.toolName(), call.arguments(),
                filePathOf(call.arguments()), null, null);
    }

    /** 从工具参数提取文件路径（path 键）。 */
    private static String filePathOf(Map<String, Object> args) {
        Object path = args.get("path");
        return path == null ? null : String.valueOf(path);
    }

    private static String onceKey(Hook hook, int index) {
        return hook.id() != null && !hook.id().isBlank() ? hook.id() : "hook@" + index;
    }

    private static String describe(Hook hook, int index) {
        String id = hook.id() != null && !hook.id().isBlank() ? hook.id() : "hook@" + index;
        return id + " (" + hook.event().name() + ")";
    }
}
