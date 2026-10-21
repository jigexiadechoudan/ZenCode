package dev.zencode.worktree;

import java.util.regex.Pattern;

/**
 * 目录名安全校验与平铺。
 * <p>
 * 允许字符集 {@code [a-zA-Z0-9_-/]}；拒绝空名、{@code .}/{@code ..} 段、绝对路径、
 * 盘符、反斜杠逃逸与非法字符；长度上限 64；{@code /} 作为嵌套分隔符，最终平铺为 {@code -}。
 * </p>
 */
public final class SlugValidator {

    private static final Pattern ALLOWED = Pattern.compile("^[a-zA-Z0-9_/-]+$");
    private static final int MAX_LENGTH = 64;

    private SlugValidator() {}

    /**
     * 校验并平铺（{@code /} → {@code -}）。非法抛 {@link IllegalArgumentException}，不执行文件操作。
     */
    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("目录名不能为空");
        }
        String trimmed = raw.trim();

        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) {
            throw new IllegalArgumentException("目录名不能是绝对路径: " + raw);
        }
        if (trimmed.contains("\\")) {
            throw new IllegalArgumentException("目录名不能含反斜杠: " + raw);
        }
        // 盘符检测：如 C:、C:/x
        if (trimmed.length() >= 2 && Character.isLetter(trimmed.charAt(0)) && trimmed.charAt(1) == ':') {
            throw new IllegalArgumentException("目录名不能是盘符路径: " + raw);
        }

        String[] segments = trimmed.split("/");
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("目录名含空段: " + raw);
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("目录名不能含 . 或 .. 段: " + raw);
            }
        }

        if (!ALLOWED.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("目录名含非法字符: " + raw);
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("目录名过长（>" + MAX_LENGTH + "）: " + raw);
        }

        String flat = flatten(trimmed);
        if (flat.isBlank()) {
            throw new IllegalArgumentException("平铺后目录名为空: " + raw);
        }
        return flat;
    }

    /** 仅平铺 {@code /} → {@code -}，不做校验。 */
    public static String flatten(String raw) {
        return raw == null ? "" : raw.replace('/', '-');
    }
}
