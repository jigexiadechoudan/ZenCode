package dev.zencode.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 已加载 Skill 的注册中心。查找、列举，整体替换（reload 用）。
 * <p>
 * 线程安全：装配期写入、命令与系统工具读取。
 * </p>
 */
public final class SkillRegistry {

    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    /** 按名字精确查找。 */
    public synchronized Optional<SkillDefinition> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(skills.get(name.toLowerCase(java.util.Locale.ROOT)));
    }

    /** 是否存在指定名字的 Skill。 */
    public synchronized boolean contains(String name) {
        return name != null && skills.containsKey(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** 全部非隐藏 Skill（用于摘要与列表展示）。 */
    public synchronized List<SkillDefinition> list() {
        List<SkillDefinition> result = new ArrayList<>();
        for (SkillDefinition skill : skills.values()) {
            if (!skill.hidden()) result.add(skill);
        }
        return List.copyOf(result);
    }

    /** 全部 Skill（含隐藏）。 */
    public synchronized List<SkillDefinition> all() {
        return List.copyOf(skills.values());
    }

    /** 全部 Skill 名。 */
    public synchronized List<String> names() {
        return List.copyOf(skills.keySet());
    }

    /** 已加载数量（含隐藏）。 */
    public synchronized int size() {
        return skills.size();
    }

    /** 整体替换加载结果（reload 用）。 */
    public synchronized void replaceAll(Map<String, SkillDefinition> loaded) {
        skills.clear();
        if (loaded != null) {
            skills.putAll(loaded);
        }
    }
}
