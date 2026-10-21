package dev.zencode.subagent;

import dev.zencode.permission.PermissionMode;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 子 Agent 定义解析器：分离 YAML frontmatter 与 Markdown body，校验并构建 {@link AgentDefinition}。
 * <p>
 * 单文件解析失败抛 {@link AgentParseException}（消息带文件+字段定位），由加载器捕获跳过，不阻断整体加载。
 * </p>
 */
public final class AgentParser {

    private AgentParser() {}

    /** 从文件解析子 Agent 定义。 */
    public static AgentDefinition parse(Path file, AgentSource source) {
        final String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AgentParseException(file.toString() + ": 读取失败: " + e.getMessage());
        }
        return parseText(text, source);
    }

    /** 从纯文本解析子 Agent 定义（测试与内置资源共用）。 */
    public static AgentDefinition parseText(String text, AgentSource source) {
        if (text == null || text.isBlank()) {
            throw new AgentParseException(source.path().toString() + ": 内容为空");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            throw new AgentParseException(source.path().toString() + ": 文件必须以 --- 开头");
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new AgentParseException(source.path().toString() + ": 缺少 frontmatter 结束标记 ---");
        }
        String frontmatter = String.join("\n", Arrays.copyOfRange(lines, 1, end));
        String body = String.join("\n", Arrays.copyOfRange(lines, end + 1, lines.length)).trim();

        Map<String, Object> fm = parseFrontmatter(frontmatter, source.path().toString());
        String path = source.path().toString();

        String name = requiredString(fm, "name", path);
        String description = requiredString(fm, "description", path);
        Optional<String> model = optionalString(fm.get("model"));
        int maxRounds = positiveInt(fm.get("maxRounds"), AgentDefinition.defaultMaxRounds(), path);
        PermissionMode permission = parsePermission(fm.get("permission"), path);
        List<String> tools = stringList(fm.get("tools"));
        List<String> disallowedTools = stringList(fm.get("disallowedTools"));
        boolean isolation = parseIsolation(fm.get("isolation"), path);

        return new AgentDefinition(name, description, model, maxRounds, permission,
                tools, disallowedTools, body, isolation, source);
    }

    private static Map<String, Object> parseFrontmatter(String frontmatter, String path) {
        final Object parsed;
        try {
            parsed = new Load(LoadSettings.builder().build()).loadFromString(frontmatter);
        } catch (RuntimeException e) {
            throw new AgentParseException(path + ": frontmatter YAML 解析失败: " + e.getMessage(), e);
        }
        if (parsed == null) {
            return Map.of();
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new AgentParseException(path + ": frontmatter 必须是键值对象");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key instanceof String name) result.put(name, value);
        });
        return result;
    }

    private static String requiredString(Map<String, Object> fm, String key, String path) {
        Object value = fm.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new AgentParseException(path + ": 缺少必填字段 " + key);
        }
        return s;
    }

    private static Optional<String> optionalString(Object value) {
        return value instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }

    /** maxRounds：正整数，缺省用默认值；支持数字或数字字符串，非法/非正报错。 */
    private static int positiveInt(Object value, int defaultValue, String path) {
        if (value == null) return defaultValue;
        final int parsed;
        if (value instanceof Number n) {
            parsed = n.intValue();
        } else if (value instanceof String s) {
            try {
                parsed = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new AgentParseException(path + ": maxRounds 非法: " + s);
            }
        } else {
            throw new AgentParseException(path + ": maxRounds 非法: " + value);
        }
        if (parsed <= 0) {
            throw new AgentParseException(path + ": maxRounds 必须为正数: " + parsed);
        }
        return parsed;
    }

    /** permission：缺省 DEFAULT，非法值报错并定位。 */
    private static PermissionMode parsePermission(Object value, String path) {
        if (value == null) return PermissionMode.DEFAULT;
        if (!(value instanceof String s) || s.isBlank()) {
            throw new AgentParseException(path + ": permission 非法: " + value);
        }
        try {
            return PermissionMode.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new AgentParseException(path + ": permission 非法: " + s);
        }
    }

    /** isolation：缺省 false，非布尔报错。 */
    private static boolean parseIsolation(Object value, String path) {
        if (value == null) return false;
        if (!(value instanceof Boolean b)) {
            throw new AgentParseException(path + ": isolation 非法: " + value);
        }
        return b;
    }

    /** tools/disallowedTools 支持字符串或字符串列表，统一转小写；非法类型忽略。 */
    private static List<String> stringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof String s && !s.isBlank()) {
            return List.of(s.toLowerCase(Locale.ROOT));
        }
        if (value instanceof List<?> raw) {
            List<String> out = new ArrayList<>();
            for (Object element : raw) {
                if (element instanceof String s && !s.isBlank()) {
                    out.add(s.toLowerCase(Locale.ROOT));
                }
            }
            return List.copyOf(out);
        }
        return List.of();
    }
}
