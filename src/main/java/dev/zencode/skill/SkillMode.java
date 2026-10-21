package dev.zencode.skill;

/**
 * Skill 执行模式。
 */
public enum SkillMode {
    /** 共享当前对话上下文执行，结果留在主对话历史。 */
    INLINE,
    /** 开一条独立对话执行，完成后结果摘要回流主对话。 */
    FORK
}
