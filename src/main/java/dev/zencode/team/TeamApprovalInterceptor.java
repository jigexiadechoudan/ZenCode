package dev.zencode.team;

import dev.zencode.agent.execution.ToolExecutionInterceptor;
import dev.zencode.permission.PermissionChecker;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 队员审批流拦截器（needApproval=true 时使用）：把需要人工确认（REQUEST_HITL）的
 * 操作转为 APPROVAL 协议消息发到 Lead 邮箱，返回「已申请审批」受限结果；
 * 队员在后续轮次通过邮箱轮询看到 Lead 的审批回复后重试。ALLOW 放行；
 * DENY_BLOCKED/DENY_RULE 受限拒绝。异步非阻塞，不弹 HITL。
 */
public final class TeamApprovalInterceptor implements ToolExecutionInterceptor {

    private final PermissionChecker checker;
    private final TeamRuntime rt;
    private final String fromAgentId;
    private final TeamApprovalState approvals;
    private final ConcurrentMap<String, String> approvedKeys = new ConcurrentHashMap<>();

    public TeamApprovalInterceptor(PermissionChecker checker, TeamRuntime rt, String fromAgentId) {
        this.checker = checker;
        this.rt = rt;
        this.fromAgentId = fromAgentId;
        this.approvals = new TeamApprovalState(rt.teamDir(), fromAgentId);
    }

    @Override
    public BeforeDecision before(ToolCall call, ToolExecutionContext context) {
        PermissionChecker.CheckResult result = checker.check(call);
        return switch (result.decision()) {
            case ALLOW -> allowFor(call);
            case DENY_BLOCKED, DENY_RULE -> new BeforeDecision.Reject(
                    ToolResult.restricted(call.callId(), call.toolName(), result.reason()));
            case REQUEST_HITL -> requestApproval(call, result.reason());
        };
    }

    /** 向 Lead 邮箱发 APPROVAL 请求并拦截当前调用。 */
    private BeforeDecision requestApproval(ToolCall call, String reason) {
        String summary = PermissionChecker.truncate(checker.safeSummary(call), 200);
        approvals.acceptReplies(rt.mailbox().readAll(fromAgentId));
        String key = checker.argumentKey(call);
        TeamApprovalState.Pending pending = approvals.findOrCreate(call.toolName(), key);
        TeamApprovalState.Request request = pending.request();
        if ("DENIED".equals(request.status())) {
            return new BeforeDecision.Reject(ToolResult.restricted(call.callId(), call.toolName(),
                    "Lead 已拒绝该操作，原请求不可重复申请"));
        }
        if ("APPROVED".equals(request.status())) {
            if (approvals.consume(request.approvalId())) return allowFor(call);
            return new BeforeDecision.Reject(ToolResult.restricted(call.callId(), call.toolName(),
                    "审批许可已被使用"));
        }
        boolean alreadyDelivered = rt.mailbox().readAll("lead").stream()
                .anyMatch(mail -> request.approvalId().equals(mail.protocol().get("approvalId")));
        if (!pending.created() && alreadyDelivered) {
            return new BeforeDecision.Reject(ToolResult.restricted(call.callId(), call.toolName(),
                    "审批请求仍在等待 Lead 回复，approvalId=" + request.approvalId()));
        }
        try {
            rt.mailbox().send(fromAgentId, "lead", MessageType.APPROVAL,
                    "审批请求: " + call.toolName(), summary,
                    Map.of("request", "approve", "tool", call.toolName(), "reason", reason,
                            "approvalId", request.approvalId(), "argumentKey", key));
        } catch (TeamException e) {
            approvals.removeFailedRequest(request.approvalId());
            return new BeforeDecision.Reject(ToolResult.restricted(
                    call.callId(), call.toolName(), "审批请求发送失败: " + e.getMessage()));
        }
        return new BeforeDecision.Reject(ToolResult.restricted(call.callId(), call.toolName(),
                "该操作需要 Lead 审批，approvalId=" + request.approvalId() + "，等待回复后重试"));
    }

    private BeforeDecision allowFor(ToolCall call) {
        approvedKeys.put(call.callId(), checker.argumentKey(call));
        return new BeforeDecision.Allow();
    }

    @Override
    public BeforeDecision revalidate(ToolCall call, ToolExecutionContext context) {
        var boundary = checker.checkExecutionBoundary(call, context.workingDirectory());
        if (boundary.decision() != dev.zencode.permission.PermissionDecision.ALLOW) {
            return new BeforeDecision.Reject(ToolResult.restricted(
                    call.callId(), call.toolName(), boundary.reason()));
        }
        String key = approvedKeys.remove(call.callId());
        if (key == null || !key.equals(checker.argumentKey(call))) {
            return new BeforeDecision.Reject(ToolResult.restricted(
                    call.callId(), call.toolName(), "审批后工具参数或路径目标发生变化"));
        }
        return new BeforeDecision.Allow();
    }

    @Override
    public ToolResult after(ToolCall call, ToolResult result, ToolExecutionContext context) {
        return result;
    }
}
