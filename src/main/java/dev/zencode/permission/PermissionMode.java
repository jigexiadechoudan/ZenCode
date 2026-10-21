package dev.zencode.permission;

import dev.zencode.tool.ToolCategory;

import java.util.Locale;

/**
 * 权限模式，覆盖从完全不信任到完全信任的光谱（对齐参考架构第六章矩阵）。
 * <p>
 * LOCKED（锁定）：全部工具（含 READ）拒绝；READ_ONLY（只读）：READ 放行，
 * WRITE/COMMAND 拒绝；DEFAULT（默认）：READ 自动放行，WRITE/COMMAND 规则未命中时
 * 转 HITL 询问；LENIENT（宽松，等价 ACCEPT_EDITS）：READ/WRITE 自动放行，
 * COMMAND 规则未命中时询问；TRUST_ALL（完全信任，等价 BYPASS）：全部放行；
 * PLAN（计划）：先走 Plan Mode 例外（计划工具与计划目录写入放行），其余委托 DEFAULT。
 * </p>
 */
public enum PermissionMode {
    LOCKED,
    READ_ONLY,
    DEFAULT,
    LENIENT,
    TRUST_ALL,
    PLAN;

    /**
     * 按权限模式在规则未命中时推导决策。
     *
     * @param category 工具分类
     * @return 该模式下规则未命中工具的兜底决策
     */
    public PermissionDecision derive(ToolCategory category) {
        switch (this) {
            case LOCKED:
                return PermissionDecision.DENY_BLOCKED;
            case READ_ONLY:
                return category == ToolCategory.READ
                        ? PermissionDecision.ALLOW : PermissionDecision.DENY_BLOCKED;
            case DEFAULT:
            case PLAN:
                return category == ToolCategory.READ
                        ? PermissionDecision.ALLOW : PermissionDecision.REQUEST_HITL;
            case LENIENT:
                return category == ToolCategory.COMMAND
                        ? PermissionDecision.REQUEST_HITL : PermissionDecision.ALLOW;
            case TRUST_ALL:
                return PermissionDecision.ALLOW;
            default:
                return PermissionDecision.REQUEST_HITL;
        }
    }

    /**
     * 从字符串解析权限模式，大小写不敏感，忽略空白。
     *
     * @throws IllegalArgumentException 非法值
     */
    public static PermissionMode fromString(String value) {
        if (value == null) throw new IllegalArgumentException("权限模式不能为空");
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "LOCKED" -> LOCKED;
            case "READ_ONLY", "READONLY" -> READ_ONLY;
            case "DEFAULT" -> DEFAULT;
            case "LENIENT" -> LENIENT;
            case "PLAN" -> PLAN;
            case "TRUST_ALL", "TRUSTALL" -> TRUST_ALL;
            default -> throw new IllegalArgumentException("未知权限模式: " + value);
        };
    }
}