package dev.zencode.tool;

/**
 * 工具权限标记：READ_ONLY（只读，无需审批）、REQUIRES_CONFIRMATION（需确认，仅标记，不触发审批流程）。
 */
public enum ToolPermission {
    READ_ONLY,
    REQUIRES_CONFIRMATION
}