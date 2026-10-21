package dev.zencode.subagent;

import java.nio.file.Path;

/**
 * 子 Agent 定义来源层级与位置。
 *
 * @param level 来源层级（优先级：项目 > 用户 > 内置 > 插件）
 * @param path  定义文件路径（<name>.md）
 */
public record AgentSource(Level level, Path path) {

    /** 来源层级，从低到高：PLUGIN → BUILTIN → USER → PROJECT。 */
    public enum Level { PLUGIN, BUILTIN, USER, PROJECT }

    public AgentSource {
        if (level == null) throw new IllegalArgumentException("来源层级不能为空");
        if (path == null) throw new IllegalArgumentException("来源路径不能为空");
    }

    /** 展示用层级名（大写）。 */
    public String levelName() {
        return level.name();
    }
}
