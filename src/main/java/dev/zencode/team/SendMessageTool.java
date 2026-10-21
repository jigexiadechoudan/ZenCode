package dev.zencode.team;

import dev.zencode.tool.BaseTool;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 点对点消息工具（工具名 {@code send_message}）：按队员名（名称注册表）解析目标
 * agentId 并写入其邮箱；to=all 广播全员。支持 text / broadcast / lifecycle / approval
 * 类型与 protocol 键值对。投递后的唤醒 / IDLE 恢复由 {@link TeamManager} 统一处理。
 * <p>
 * 实例绑定发送方身份（from）与所属团队：队员池注入时 from=队员 agentId；
 * Lead 主注册中心注册时 from="lead"。
 * </p>
 */
public final class SendMessageTool extends BaseTool {

    private final TeamRuntime rt;
    private final TeamManager manager;
    private final String from;

    public SendMessageTool(TeamRuntime rt, TeamManager manager, String from) {
        super("send_message",
                "给团队成员或 Lead 发送点对点消息：to 为队员名或 all（广播）。"
                        + "type 支持 text/broadcast/lifecycle/approval；protocol 为 k=v,k2=v2 协议字段。"
                        + "审批回复必须回传 request=reply、approvalId、tool、argumentKey 和 allow=true/false。",
                ToolCategory.COMMAND, ToolPermission.REQUIRES_CONFIRMATION,
                jsonSchema(Map.of(
                        "to", stringProp("接收方队员名或 all（广播）"),
                        "message", stringProp("消息正文"),
                        "type", stringProp("text / broadcast / lifecycle / approval"),
                        "subject", stringProp("摘要，可选"),
                        "protocol", stringProp("协议字段 k=v,k2=v2，可选")
                ), List.of("to", "message")));
        this.rt = rt;
        this.manager = manager;
        this.from = from;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String to = requiredString(args, "to");
        String message = requiredString(args, "message");
        String typeText = optionalString(args, "type", "text");
        String subject = optionalString(args, "subject", "");
        Map<String, String> protocol = parseProtocol(optionalString(args, "protocol", ""));

        MessageType type;
        try {
            type = MessageType.valueOf(typeText.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(ctx.callId(), name(), "未知消息类型: " + typeText);
        }

        if (to.equalsIgnoreCase("all")) {
            type = MessageType.BROADCAST;
        }

        try {
            if (manager == null) {
                // 外部队员进程（tmux/iTerm2 中的 headless CLI）：只投递，唤醒/恢复由 Lead 侧负责
                deliverOnly(rt, from, to, type, subject, message, protocol);
                return ToolResult.success(ctx.callId(), name(),
                        to.equalsIgnoreCase("all") ? "已广播" : "消息已发送给 " + to);
            }
            String delivered = manager.sendMessage(rt, from, to, type, subject, message, protocol);
            return ToolResult.success(ctx.callId(), name(), delivered);
        } catch (TeamException e) {
            return ToolResult.error(ctx.callId(), name(), e.getMessage());
        }
    }

    /** 无 manager 时的投递：名称解析（all=广播）→ 写邮箱。 */
    private static void deliverOnly(TeamRuntime rt, String from, String to, MessageType type,
                                    String subject, String body, Map<String, String> protocol) {
        if (type == MessageType.BROADCAST || "all".equalsIgnoreCase(to)) {
            List<String> targets = rt.members().stream()
                    .map(TeamMember::agentId).filter(id -> !id.equals(from)).toList();
            rt.mailbox().broadcast(from, targets, MessageType.BROADCAST, subject, body, protocol);
            return;
        }
        TeamMember target = rt.member(to).orElseThrow(() -> new TeamException("未知队员: " + to));
        rt.mailbox().send(from, target.agentId(), type, subject, body, protocol);
    }

    /** 解析 k=v,k2=v2 协议字段。 */
    private static Map<String, String> parseProtocol(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            String trimmed = pair.trim();
            if (trimmed.isBlank()) continue;
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        }
        return out;
    }
}
