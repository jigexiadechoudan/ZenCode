package dev.zencode.skill;

import java.util.Locale;

/**
 * fork 模式独立对话的历史携带策略。
 *
 * @param type        携带类型：FULL 全部历史 / RECENT 最近 N 条 / NONE 不带历史
 * @param recentCount RECENT 时的条数（其他类型为 0）
 */
public record SkillContextPolicy(ContextType type, int recentCount) {

    public enum ContextType { FULL, RECENT, NONE }

    public SkillContextPolicy {
        if (type == null) throw new IllegalArgumentException("context 类型不能为空");
        if (recentCount < 0) throw new IllegalArgumentException("recentCount 不能为负");
    }

    public static SkillContextPolicy full() {
        return new SkillContextPolicy(ContextType.FULL, 0);
    }

    public static SkillContextPolicy none() {
        return new SkillContextPolicy(ContextType.NONE, 0);
    }

    public static SkillContextPolicy recent(int count) {
        if (count < 1) throw new IllegalArgumentException("recent 条数必须 >= 1");
        return new SkillContextPolicy(ContextType.RECENT, count);
    }

    /**
     * 解析 frontmatter context 字符串。
     * "full" → FULL；"none" / 空 / null → NONE；"recent" → RECENT(10)；
     * "recent &lt;N&gt;" → RECENT(N)。非法值抛 {@link SkillParseException}。
     */
    public static SkillContextPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) return none();
        String s = raw.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "full" -> { return full(); }
            case "none" -> { return none(); }
            case "recent" -> { return recent(10); }
        }
        if (s.startsWith("recent ")) {
            String countText = s.substring("recent ".length()).trim();
            try {
                int count = Integer.parseInt(countText);
                if (count > 0) return recent(count);
            } catch (NumberFormatException ignored) {
                // 落入下方异常
            }
        }
        throw new SkillParseException("非法 context 值: " + raw);
    }
}
