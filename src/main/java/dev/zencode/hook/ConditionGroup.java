package dev.zencode.hook;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 条件组：ALL（全部满足）或 ANY（任一满足），二选一，不允许混用。
 */
public final class ConditionGroup {

    public enum GroupMode { ALL, ANY }

    private final GroupMode mode;
    private final List<Condition> conditions;

    public ConditionGroup(GroupMode mode, List<Condition> conditions) {
        this.mode = mode == null ? GroupMode.ALL : mode;
        this.conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    public GroupMode mode() {
        return mode;
    }

    public List<Condition> conditions() {
        return conditions;
    }

    /** 条件为空时无条件匹配。 */
    public boolean matches(HookContext context) {
        if (conditions.isEmpty()) {
            return true;
        }
        if (mode == GroupMode.ALL) {
            for (Condition condition : conditions) {
                if (!matchesOne(condition, context)) return false;
            }
            return true;
        }
        for (Condition condition : conditions) {
            if (matchesOne(condition, context)) return true;
        }
        return false;
    }

    private static boolean matchesOne(Condition condition, HookContext context) {
        String actual = context.fieldValue(condition.field());
        String expected = condition.value();
        return switch (condition.operator()) {
            case EQ -> expected.equals(actual);
            case NE -> !expected.equals(actual);
            case REGEX -> Pattern.compile(expected).matcher(actual).find();
            case GLOB -> globToRegex(expected).matcher(actual).matches();
        };
    }

    /** 将 glob 模式转换为大小写不敏感正则（与权限规则语义一致）。 */
    private static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            switch (ch) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) regex.append('\\');
                    regex.append(ch);
                }
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}
