package dev.zencode.permission.hitl;

/**
 * HITL 用户决策。
 * <p>
 * DENY：拒绝，不记忆；ALLOW_ONCE：本次允许，不记忆；
 * ALLOW_SESSION：写入会话级规则；ALLOW_FOREVER：永久允许写入项目级规则；
 * DENY_FOREVER：永久拒绝写入项目级规则。
 * </p>
 */
public enum HitlDecision {
    DENY,
    ALLOW_ONCE,
    ALLOW_SESSION,
    ALLOW_FOREVER,
    DENY_FOREVER
}