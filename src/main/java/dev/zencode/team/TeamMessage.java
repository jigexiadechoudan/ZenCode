package dev.zencode.team;

import java.util.Map;

/**
 * 邮箱消息。seq 在团队邮箱内单调递增；protocol 承载结构化负载
 * （lifecycle 的 event、approval 的 request/reply + tool + allow）。
 *
 * @param seq       顺序号
 * @param type      消息类型
 * @param from      发送方 agentId 或 "lead"
 * @param to        接收方 agentId 或 "all"（广播）
 * @param subject   纯文本摘要
 * @param body      正文
 * @param timestamp 时间戳（毫秒）
 * @param protocol  结构化协议字段
 */
public record TeamMessage(
        long seq,
        MessageType type,
        String from,
        String to,
        String subject,
        String body,
        long timestamp,
        Map<String, String> protocol) {

    public TeamMessage {
        if (type == null) throw new IllegalArgumentException("消息类型不能为空");
        if (from == null || from.isBlank()) throw new IllegalArgumentException("发送方不能为空");
        if (to == null || to.isBlank()) throw new IllegalArgumentException("接收方不能为空");
        subject = subject == null ? "" : subject;
        body = body == null ? "" : body;
        protocol = protocol == null ? Map.of() : Map.copyOf(protocol);
    }
}
