package dev.zencode.skill;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 会话级激活 Skill 列表（线程安全）。
 * <p>
 * 系统工具 LoadSkill/ActivateSkill 写入，Agent 每轮请求组装（RequestAssembler）读取：
 * 激活指令 transient 注入 + 白名单并集计算。{@code /clear} 时整体清空。
 * 同一 Skill 再激活时去重置顶（后激活在前）。
 * </p>
 */
public final class ActiveSkills {

    private final Map<String, ActivatedSkill> active = new LinkedHashMap<>();

    /** 激活 Skill，参数用于替换 $ARGUMENTS。 */
    public synchronized void activate(SkillDefinition skill, String arguments) {
        if (skill == null) return;
        active.remove(skill.name());
        active.put(skill.name(), new ActivatedSkill(skill, skill.bodyWith(arguments)));
    }

    /** 失活指定 Skill。 */
    public synchronized void deactivate(String name) {
        if (name == null) return;
        active.remove(name.toLowerCase(Locale.ROOT));
    }

    /** 清空全部激活（/clear 时调用）。 */
    public synchronized void clear() {
        active.clear();
    }

    /** 是否无任何激活。 */
    public synchronized boolean isEmpty() {
        return active.isEmpty();
    }

    /** 当前激活项快照（不可变，插入序即激活序）。 */
    public synchronized List<ActivatedSkill> snapshot() {
        return List.copyOf(active.values());
    }

    /** 当前激活的 Skill 名（保持激活序）。 */
    public synchronized List<String> names() {
        return List.copyOf(active.keySet());
    }

    /**
     * 全部激活 Skill 的 allowedTools 白名单并集（小写）。空集=不限。
     * 不包含系统工具名（系统工具恒放行）。
     */
    public synchronized Set<String> allowedToolUnion() {
        Set<String> union = new HashSet<>();
        for (ActivatedSkill item : active.values()) {
            for (String tool : item.skill().allowedTools()) {
                union.add(tool.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(union);
    }
}
