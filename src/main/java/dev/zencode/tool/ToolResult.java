package dev.zencode.tool;

import java.util.Map;
import java.util.Objects;

/**
 * 不可变的统一工具执行结果。
 * <p>
 * 提供成功、失败、参数错误、禁用、超时和取消等工厂方法，避免具体工具直接构造不一致的结果。
 * 所有结果包含调用 ID、工具名称、状态码、内容和截断标记。
 * </p>
 */
public final class ToolResult {
    private final String callId;
    private final String toolName;
    private final boolean success;
    private final Status status;
    private final String content;
    private final boolean truncated;

    private ToolResult(String callId, String toolName, boolean success, Status status,
                       String content, boolean truncated) {
        this.callId = Objects.requireNonNull(callId, "callId 不能为空");
        this.toolName = Objects.requireNonNull(toolName, "toolName 不能为空");
        this.success = success;
        this.status = Objects.requireNonNull(status, "status 不能为空");
        this.content = content == null ? "" : content;
        this.truncated = truncated;
    }

    /** 工具调用 ID。 */
    public String callId() { return callId; }

    /** 工具名称。 */
    public String toolName() { return toolName; }

    /** 是否成功。 */
    public boolean success() { return success; }

    /** 稳定状态码。 */
    public Status status() { return status; }

    /** 提供给模型的结果内容或安全错误说明。 */
    public String content() { return content; }

    /** 结果是否因 32 KiB 上限被截断。 */
    public boolean truncated() { return truncated; }

    // ---- 工厂方法 ----

    /** 创建成功结果，内容不截断。 */
    public static ToolResult success(String callId, String toolName, String content) {
        return new ToolResult(callId, toolName, true, Status.SUCCESS, content, false);
    }

    /** 创建成功但被截断的结果。 */
    public static ToolResult truncated(String callId, String toolName, String content) {
        return new ToolResult(callId, toolName, true, Status.TRUNCATED, content, true);
    }

    /** 创建通用失败结果。 */
    public static ToolResult error(String callId, String toolName, String content) {
        return new ToolResult(callId, toolName, false, Status.ERROR, content, false);
    }

    /** 创建参数错误结果（工具未执行）。 */
    public static ToolResult invalidArgument(String callId, String toolName, String content) {
        return new ToolResult(callId, toolName, false, Status.INVALID_ARGUMENT, content, false);
    }

    /** 创建工具禁用结果（工具未执行）。 */
    public static ToolResult disabled(String callId, String toolName) {
        return new ToolResult(callId, toolName, false, Status.DISABLED, "工具已禁用: " + toolName, false);
    }

    /** 创建超时结果。 */
    public static ToolResult timeout(String callId, String toolName, String content) {
        return new ToolResult(callId, toolName, false, Status.TIMEOUT, content, false);
    }

    /** 创建取消结果。 */
    public static ToolResult cancelled(String callId, String toolName, String content) {
        return new ToolResult(callId, toolName, false, Status.CANCELLED, content, false);
    }

    /** 创建 plan-only 受限结果（工具未执行）。 */
    public static ToolResult restricted(String callId, String toolName, String content) {
        return new ToolResult(callId, toolName, false, Status.RESTRICTED, content, false);
    }

    /** 创建未知工具结果。 */
    public static ToolResult unknownTool(String callId, String toolName) {
        return new ToolResult(callId, toolName, false, Status.ERROR, "未知工具: " + toolName, false);
    }

    /** 稳定状态码。 */
    public enum Status {
        SUCCESS,
        ERROR,
        INVALID_ARGUMENT,
        DISABLED,
        TIMEOUT,
        CANCELLED,
        TRUNCATED,
        RESTRICTED
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ToolResult that)) return false;
        return success == that.success && truncated == that.truncated
                && callId.equals(that.callId) && toolName.equals(that.toolName)
                && status == that.status && content.equals(that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(callId, toolName, success, status, content, truncated);
    }

    @Override
    public String toString() {
        return "ToolResult{" + "callId='" + callId + '\'' + ", toolName='" + toolName + '\''
                + ", success=" + success + ", status=" + status + ", truncated=" + truncated + '}';
    }
}