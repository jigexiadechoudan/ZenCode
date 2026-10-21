package dev.zencode.permission.rule;

/**
 * 规则层级与优先级：SESSION（会话级，内存，最高）> PROJECT（项目级，文件）> USER（用户级，文件）。
 */
public enum RuleLayer {
    SESSION,
    PROJECT,
    USER;

    /**
     * 从字符串解析层级，忽略大小写。
     *
     * @throws IllegalArgumentException 非法值
     */
    public static RuleLayer fromString(String value) {
        if (value == null) throw new IllegalArgumentException("规则层级不能为空");
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SESSION" -> SESSION;
            case "PROJECT" -> PROJECT;
            case "USER" -> USER;
            default -> throw new IllegalArgumentException("未知规则层级: " + value);
        };
    }
}