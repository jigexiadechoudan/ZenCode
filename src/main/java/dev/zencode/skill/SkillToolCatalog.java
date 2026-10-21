package dev.zencode.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 目录型 Skill 的 tool.json 解析与工具注册。
 * <p>
 * tool.json 结构：{ "tools": [ { "name", "description", "schema", "script" } ] }。
 * 脚本相对 skill 目录解析；.ps1 → PowerShell，.bat/.cmd → cmd。
 * </p>
 */
public final class SkillToolCatalog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SkillToolCatalog() {}

    /** 解析 skill 目录的 tool.json，返回工具声明列表；目录不存在或无 tool.json 返回空。 */
    public static List<SkillToolSpec> parse(Path skillDir) {
        Path toolJson = skillDir.resolve("tool.json");
        if (!Files.isRegularFile(toolJson)) return List.of();
        final Map<String, Object> root;
        try {
            root = MAPPER.readValue(toolJson.toFile(), new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new SkillParseException(skillDir.toString(), "tool.json 读取失败: " + e.getMessage(), e);
        }
        Object rawTools = root.get("tools");
        if (!(rawTools instanceof List<?> list)) {
            throw new SkillParseException(skillDir.toString(), "tool.json 缺少 tools 列表");
        }
        List<SkillToolSpec> result = new ArrayList<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> item)) continue;
            String name = str(item.get("name"));
            String scriptText = str(item.get("script"));
            if (name == null || name.isBlank() || scriptText == null || scriptText.isBlank()) continue;
            Path script = skillDir.resolve(scriptText).normalize();
            ScriptKind kind = detectKind(script, skillDir.toString());
            String description = str(item.get("description"));
            Map<String, Object> schema = schemaMap(item.get("schema"));
            result.add(new SkillToolSpec(name, description == null ? "" : description, schema, script, kind));
        }
        return List.copyOf(result);
    }

    /** 目录型 Skill 声明的全部工具名（fail-fast 依赖校验用）。 */
    public static List<String> toolNames(Path skillDir) {
        return parse(skillDir).stream().map(SkillToolSpec::name).toList();
    }

    /** 注册目录型 Skill 的全部自带工具到工具注册中心。 */
    public static void registerTools(Path skillDir, dev.zencode.tool.ToolRegistry registry) {
        for (SkillToolSpec spec : parse(skillDir)) {
            registry.register(new SkillTool(spec, skillDir));
        }
    }

    private static ScriptKind detectKind(Path script, String path) {
        String name = script.getFileName() == null ? "" : script.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".ps1")) return ScriptKind.POWERSHELL;
        if (name.endsWith(".bat") || name.endsWith(".cmd")) return ScriptKind.CMD;
        throw new SkillParseException(path, "不支持的脚本扩展名: " + script + "（仅支持 .ps1/.bat/.cmd）");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (k instanceof String key) out.put(key, v);
            });
            return out;
        }
        return Map.of();
    }

    private static String str(Object value) {
        return value instanceof String s ? s : null;
    }
}
