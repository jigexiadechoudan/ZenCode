package dev.zencode.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 条件表达式解析：把 if 字符串解析为 {@link ConditionGroup}。
 * <p>支持纯 &&（ALL）或纯 ||（ANY）组合，混用抛 {@link HookConfigException}；
 * 字段白名单为 event/tool/file_path/message/error 或 args. 前缀。</p>
 */
public final class ConditionParser {

    private static final Set<String> FIELDS =
            Set.of("event", "tool", "file_path", "message", "error");
    private static final String[] OPERATORS = {"==", "!=", "=~", "~="};

    private ConditionParser() {
    }

    /** 解析表达式；空或空白返回 null（无条件）。 */
    public static ConditionGroup parse(String expr) {
        if (expr == null || expr.isBlank()) {
            return null;
        }
        String trimmed = expr.trim();
        boolean hasAnd = trimmed.contains("&&");
        boolean hasOr = trimmed.contains("||");
        if (hasAnd && hasOr) {
            throw new HookConfigException("条件不允许混用 && 与 ||: " + expr);
        }
        String separator = hasOr ? "\\|\\|" : "&&";
        String[] parts = trimmed.split(separator);
        List<Condition> conditions = new ArrayList<>(parts.length);
        for (String part : parts) {
            conditions.add(parseCondition(part.trim(), expr));
        }
        return new ConditionGroup(hasOr ? ConditionGroup.GroupMode.ANY : ConditionGroup.GroupMode.ALL,
                conditions);
    }

    private static Condition parseCondition(String token, String original) {
        int opIndex = -1;
        String opSymbol = null;
        for (String op : OPERATORS) {
            int idx = token.indexOf(op);
            if (idx >= 0 && (opIndex < 0 || idx < opIndex)) {
                opIndex = idx;
                opSymbol = op;
            }
        }
        if (opSymbol == null) {
            throw new HookConfigException("条件缺少操作符: " + original);
        }
        String field = token.substring(0, opIndex).trim();
        String rawValue = token.substring(opIndex + opSymbol.length()).trim();
        if (field.isEmpty()) {
            throw new HookConfigException("条件缺少字段: " + original);
        }
        if (!FIELDS.contains(field) && !field.startsWith("args.")) {
            throw new HookConfigException("未知条件字段: " + field + "（" + original + "）");
        }
        String value = unquote(rawValue, original);
        Operator operator = switch (opSymbol) {
            case "==" -> Operator.EQ;
            case "!=" -> Operator.NE;
            case "=~" -> Operator.REGEX;
            default -> Operator.GLOB;
        };
        if (operator == Operator.REGEX) {
            try {
                Pattern.compile(value);
            } catch (PatternSyntaxException e) {
                throw new HookConfigException("正则无效: " + value + "（" + original + "）");
            }
        }
        return new Condition(field, operator, value);
    }

    private static String unquote(String raw, String original) {
        String s = raw.trim();
        if (s.isEmpty()) {
            throw new HookConfigException("条件缺少值: " + original);
        }
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
