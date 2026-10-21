package dev.zencode.permission;

/**
 * 权限检查的最终决策。
 * <p>
 * ALLOW：放行执行；DENY_BLOCKED：硬防线拒绝（危险命令/路径越界/模式锁定）；
 * DENY_RULE：规则显式拒绝；REQUEST_HITL：规则与模式均未决，需转 HITL 询问。
 * </p>
 */
public enum PermissionDecision {
    ALLOW,
    DENY_BLOCKED,
    DENY_RULE,
    REQUEST_HITL;

    /** 该决策是否属于拒绝类（硬拦或规则拒绝）。 */
    public boolean isDeny() {
        return this == DENY_BLOCKED || this == DENY_RULE;
    }
}