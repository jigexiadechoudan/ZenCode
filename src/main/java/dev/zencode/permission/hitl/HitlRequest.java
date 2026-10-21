package dev.zencode.permission.hitl;

/**
 * 一条待 HITL 确认的请求。
 * <p>
 * 只携带脱敏安全摘要，不携带未经脱敏的完整参数或结果。
 * </p>
 */
public record HitlRequest(String callId, String toolName, String safeSummary, String reason) {

    public HitlRequest {
        if (callId == null || callId.isBlank()) throw new IllegalArgumentException("调用 ID 不能为空");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("工具名不能为空");
        safeSummary = safeSummary == null ? "" : safeSummary;
        reason = reason == null ? "" : reason;
    }
}