package dev.zencode.team;

/**
 * 邮箱消息类型。TEXT 纯文本带摘要；BROADCAST 广播；LIFECYCLE 生命周期协议消息；
 * APPROVAL 审批协议消息（请求/回复）。
 */
public enum MessageType {
    TEXT,
    BROADCAST,
    LIFECYCLE,
    APPROVAL
}
