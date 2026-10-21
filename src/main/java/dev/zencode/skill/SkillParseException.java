package dev.zencode.skill;

/**
 * Skill 解析或校验失败异常。单个 Skill 失败由加载器捕获跳过，不阻断整体。
 */
public final class SkillParseException extends RuntimeException {

    public SkillParseException(String message) {
        super(message);
    }

    public SkillParseException(String skillName, String message) {
        super("Skill[" + skillName + "] " + message);
    }

    public SkillParseException(String skillName, String message, Throwable cause) {
        super("Skill[" + skillName + "] " + message, cause);
    }
}
