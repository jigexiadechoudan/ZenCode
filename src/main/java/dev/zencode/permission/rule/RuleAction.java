package dev.zencode.permission.rule;

/**
 * 规则动作：ALLOW（放行）、DENY（拒绝，不询问）、ASK（转 HITL 询问）。
 */
public enum RuleAction {
    ALLOW,
    DENY,
    ASK
}