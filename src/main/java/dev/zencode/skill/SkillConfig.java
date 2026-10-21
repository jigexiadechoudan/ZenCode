package dev.zencode.skill;

/**
 * Skill 系统配置。
 *
 * @param maxBodyChars Skill 正文注入大小上限（字符数），默认 16384，超出截断
 */
public record SkillConfig(int maxBodyChars) {

    /** 项目级与用户级 Skill 存放相对目录。 */
    public static final String RELATIVE_DIR = ".zencode/skills";

    public SkillConfig {
        if (maxBodyChars < 1) throw new IllegalArgumentException("maxBodyChars 必须 >= 1");
    }

    public static SkillConfig defaults() {
        return new SkillConfig(16384);
    }
}
