package dev.zencode.hook;

/** prompt 动作：把 message 注入到后续请求的 transient reminders。 */
public record PromptAction(String message) implements Action {

    public PromptAction {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("prompt 动作缺少 message");
        }
    }
}
