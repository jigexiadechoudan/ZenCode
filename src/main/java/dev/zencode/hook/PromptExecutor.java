package dev.zencode.hook;

/** prompt 执行器：返回变量替换后的消息文本，注入副作用由 HookEngine 完成。 */
public final class PromptExecutor implements HookActionExecutor {

    @Override
    public String execute(Action action, HookContext context) {
        if (!(action instanceof PromptAction prompt)) {
            throw new IllegalArgumentException("PromptExecutor 只接受 prompt 动作");
        }
        return VariableResolver.resolve(prompt.message(), context);
    }
}
