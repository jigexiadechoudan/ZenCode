package dev.zencode.skill;

import java.nio.file.Path;
import java.util.Map;

/**
 * 目录型 Skill 自带的单个工具声明（来自 tool.json）。
 *
 * @param name        工具名
 * @param description 工具描述
 * @param schema      工具参数 JSON Schema
 * @param script      实现脚本路径（绝对化）
 * @param kind        脚本解释器
 */
public record SkillToolSpec(String name, String description, Map<String, Object> schema,
                            Path script, ScriptKind kind) {

    public SkillToolSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("工具名不能为空");
        if (script == null) throw new IllegalArgumentException("工具脚本路径不能为空");
        description = description == null ? "" : description;
        schema = schema == null ? Map.of() : Map.copyOf(schema);
        if (kind == null) throw new IllegalArgumentException("脚本解释器不能为空");
    }
}
