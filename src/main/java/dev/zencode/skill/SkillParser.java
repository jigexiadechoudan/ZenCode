package dev.zencode.skill;

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
 * Skill 定义解析器：分离 YAML frontmatter 与 Markdown body，校验并构建 {@link SkillDefinition}。
 * <p>
 * 单文件解析失败抛 {@link SkillParseException}，由加载器捕获跳过，不阻断整体加载。
 * </p>
 */
public final class SkillParser {

    private SkillParser() {}

    /**
     * 从文件解析 Skill。
     *
     * @param file   SKILL 定义文件（<name>.md 或 <name>/SKILL.md）
     * @param source 来源层级与位置
     */
    public static SkillDefinition parse(Path file, SkillSource source) {
        final String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SkillParseException(file.toString(), "读取失败: " + e.getMessage());
        }
        return parseText(text, source);
    }

    /** 从纯文本解析 Skill（测试与内置资源共用）。 */
    public static SkillDefinition parseText(String text, SkillSource source) {
        if (text == null || text.isBlank()) {
            throw new SkillParseException(source.path().toString(), "内容为空");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            throw new SkillParseException(source.path().toString(), "文件必须以 --- 开头");
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new SkillParseException(source.path().toString(), "缺少 frontmatter 结束标记 ---");
        }
        String frontmatter = String.join("\n", Arrays.copyOfRange(lines, 1, end));
        String body = String.join("\n", Arrays.copyOfRange(lines, end + 1, lines.length)).trim();

        Map<String, Object> fm = parseFrontmatter(frontmatter, source.path().toString());

        String name = requiredString(fm, "name", source.path().toString(), "缺少必填字段 name");
        String description = requiredString(fm, "description", source.path().toString(), "缺少必填字段 description");
        String modeText = requiredString(fm, "mode", source.path().toString(), "缺少必填字段 mode");
        SkillMode mode = switch (modeText.toLowerCase(Locale.ROOT)) {
            case "inline" -> SkillMode.INLINE;
            case "fork" -> SkillMode.FORK;
            default -> throw new SkillParseException(source.path().toString(), "非法 mode: " + modeText);
        };
        List<String> allowedTools = stringList(fm.get("allowedTools"));
        Optional<String> model = optionalString(fm.get("model"));
        String contextText = fm.containsKey("context") ? String.valueOf(fm.get("context")) : null;
        SkillContextPolicy context;
        try {
            context = SkillContextPolicy.parse(contextText);
        } catch (SkillParseException e) {
            throw new SkillParseException(source.path().toString(), e.getMessage());
        }
        boolean hidden = fm.get("hidden") instanceof Boolean b && b;

        return new SkillDefinition(name, description, allowedTools, mode, model, context, hidden, body, source);
    }

    private static Map<String, Object> parseFrontmatter(String frontmatter, String path) {
        final Object parsed;
        try {
            parsed = new Load(LoadSettings.builder().build()).loadFromString(frontmatter);
        } catch (RuntimeException e) {
            throw new SkillParseException(path, "frontmatter YAML 解析失败: " + e.getMessage(), e);
        }
        if (parsed == null) {
            return Map.of();
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new SkillParseException(path, "frontmatter 必须是键值对象");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key instanceof String name) result.put(name, value);
        });
        return result;
    }

    private static String requiredString(Map<String, Object> fm, String key, String path, String message) {
        Object value = fm.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new SkillParseException(path, message);
        }
        return s;
    }

    private static Optional<String> optionalString(Object value) {
        return value instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }

    /** allowedTools 支持字符串或字符串列表，统一转小写；非法类型忽略。 */
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
