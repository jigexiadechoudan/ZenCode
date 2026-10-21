package dev.zencode.hook;

/** agent 执行器：占位，真实实现留给后续 SubAgent 章节。 */
public final class AgentExecutor implements HookActionExecutor {

    private static final String PLACEHOLDER = "agent 执行器未实现（留给 SubAgent 章节）";

    @Override
    public String execute(Action action, HookContext context) {
        return PLACEHOLDER;
    }
}
