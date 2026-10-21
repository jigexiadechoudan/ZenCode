package dev.zencode.subagent;

import dev.zencode.permission.PermissionMode;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 子 Agent 角色的声明式定义：唯一名、说明、可选模型、轮数上限、权限模式、
 * 工具白/黑名单与指令正文。
 *
 * @param name            唯一名（小写）
 * @param description     一句话说明
 * @param model           可选独立模型；空=跟随主模型
 * @param maxRounds       最大轮数（默认 20，非正拒绝）
 * @param permissionMode  权限模式（复用 {@link PermissionMode}）
 * @param tools           工具白名单；空列表=不限制
 * @param disallowedTools 工具黑名单；空列表=不禁止
 * @param body            指令正文（Markdown）
 * @param isolation       是否隔离子 Agent（独立 worktree 目录执行），默认 false
 * @param source          来源层级与位置
 */
public record AgentDefinition(
        String name,
        String description,
        Optional<String> model,
        int maxRounds,
        PermissionMode permissionMode,
        List<String> tools,
        List<String> disallowedTools,
        String body,
        boolean isolation,
        AgentSource source) {

    public AgentDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Agent 名不能为空");
        name = name.toLowerCase(Locale.ROOT);
        if (description == null || description.isBlank()) throw new IllegalArgumentException("Agent 描述不能为空");
        model = model == null ? Optional.empty() : model;
        if (maxRounds <= 0) throw new IllegalArgumentException("最大轮数必须为正数: " + maxRounds);
        if (permissionMode == null) throw new IllegalArgumentException("权限模式不能为空");
        tools = tools == null ? List.of() : List.copyOf(tools);
        disallowedTools = disallowedTools == null ? List.of() : List.copyOf(disallowedTools);
        if (body == null) throw new IllegalArgumentException("Agent 正文不能为空");
        if (source == null) throw new IllegalArgumentException("Agent 来源不能为空");
    }

    /** 默认最大轮数。 */
    public static int defaultMaxRounds() {
        return 20;
    }
}
