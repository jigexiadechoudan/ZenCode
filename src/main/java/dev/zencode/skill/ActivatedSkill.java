package dev.zencode.skill;

/**
 * 已激活的 Skill 项。
 *
 * @param skill   Skill 定义
 * @param prompt  已替换 $ARGUMENTS 的完整指令文本（每轮 transient 注入用）
 */
public record ActivatedSkill(SkillDefinition skill, String prompt) {

    public ActivatedSkill {
        if (skill == null) throw new IllegalArgumentException("Skill 不能为空");
    }
}
