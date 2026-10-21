package dev.zencode.skill;

import java.nio.file.Path;

/**
 * Skill 来源层级与位置。
 *
 * @param level     来源层级（项目 > 用户 > 内置）
 * @param path      文件型指向 <name>.md；目录型指向 <name>/SKILL.md
 * @param directory 是否为目录型 Skill（带 tool.json + references/ 能力包）
 */
public record SkillSource(Level level, Path path, boolean directory) {

    public enum Level { PROJECT, USER, BUILTIN }

    public SkillSource {
        if (level == null) throw new IllegalArgumentException("来源层级不能为空");
        if (path == null) throw new IllegalArgumentException("来源路径不能为空");
    }

    /** 展示用层级名（大写）。 */
    public String levelName() {
        return level.name();
    }

    /** 目录型 Skill 所在目录（SKILL.md / <name>.md 的父目录），自带工具与 tool.json 所在处。 */
    public Path skillDir() {
        Path p = path.getParent();
        return p == null ? Path.of(".") : p;
    }
}
