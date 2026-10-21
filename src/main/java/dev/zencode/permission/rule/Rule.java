package dev.zencode.permission.rule;

import java.util.Objects;

/**
 * 一条权限规则：工具名 + glob 或 sha256 精确参数指纹 + 动作。
 * <p>
 * tool 为 null 或 {@code *} 时匹配所有工具。
 * glob 模式匹配对象：Bash=命令全文，Read/Write/Edit/Glob/Grep=文件路径。
 * </p>
 */
public record Rule(String tool, String pattern, RuleAction action) {

    public Rule {
        pattern = Objects.requireNonNull(pattern, "规则模式不能为空");
        action = Objects.requireNonNull(action, "规则动作不能为空");
    }

    /** 是否匹配所有工具。 */
    public boolean matchesAllTools() {
        return tool == null || tool.isBlank() || "*".equals(tool);
    }
}
