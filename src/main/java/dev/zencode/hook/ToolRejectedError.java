package dev.zencode.hook;

/**
 * 工具调用被 Hook 拒绝的结果，携带拒绝原因。
 * <p>不是普通异常兜底，而是可预期的策略结果：由拦截器转换为受限工具结果反馈给 LLM。</p>
 */
public final class ToolRejectedError extends RuntimeException {
    private final String callId;
    private final String toolName;
    private final String reason;

    public ToolRejectedError(String callId, String toolName, String reason) {
        super(reason == null || reason.isBlank() ? "被 Hook 规则拒绝" : reason);
        this.callId = callId;
        this.toolName = toolName;
        this.reason = super.getMessage();
    }

    public String callId() {
        return callId;
    }

    public String toolName() {
        return toolName;
    }

    public String reason() {
        return reason;
    }
}
