package dev.zencode.skill;

import java.util.Set;

/**
 * 系统级工具名常量。这些工具不受 Skill 白名单过滤（保证 Skill 可嵌套触发与自主激活）。
 */
public final class SystemToolNames {

    public static final String LOAD_SKILL = "LoadSkill";
    public static final String ACTIVATE_SKILL = "ActivateSkill";

    public static final Set<String> ALL = Set.of(LOAD_SKILL, ACTIVATE_SKILL);

    private SystemToolNames() {}
}
