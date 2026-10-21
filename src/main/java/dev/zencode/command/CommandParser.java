package dev.zencode.command;

import java.util.Locale;

/**
 * 命令解析器：识别 {@code /} 前缀输入，切分命令名与参数。
 * <p>
 * 首个空格之前是命令名（转小写做大小写不敏感匹配），之后是参数（去首尾空白）。
 * 不以 {@code /} 开头或仅一个 {@code /} 的输入返回 null（非命令）。
 * </p>
 */
public final class CommandParser {

    private CommandParser() {}

    /**
     * 解析输入。
     *
     * @param input 用户输入（可含前后空白）
     * @return 解析结果；非 / 命令返回 null
     */
    public static ParsedCommand parse(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '/') {
            return null;
        }
        // 去掉开头的 /，得到 "name args" 或 "name"
        String rest = trimmed.substring(1).trim();
        if (rest.isEmpty()) {
            return null;
        }
        int space = rest.indexOf(' ');
        String name;
        String args;
        if (space < 0) {
            name = rest;
            args = "";
        } else {
            name = rest.substring(0, space);
            args = rest.substring(space + 1).trim();
        }
        if (name.isEmpty()) {
            return null;
        }
        return new ParsedCommand(name.toLowerCase(Locale.ROOT), args);
    }
}