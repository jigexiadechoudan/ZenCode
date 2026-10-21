package dev.zencode.hook;

/** Hook 动作：四种执行器类型。 */
public sealed interface Action permits CommandAction, PromptAction, HttpAction, AgentAction {
}
