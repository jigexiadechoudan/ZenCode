package dev.zencode.subagent;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 子 Agent 工具过滤的四层规则，合并语义 =「允许集取交集、禁止集取并集」。
 *
 * @param globalDeny      全局禁止（含委派工具自身，防嵌套）；空=不禁止
 * @param roleAllow       角色白名单；空=不限
 * @param roleDeny        角色黑名单；空=不禁止
 * @param backgroundAllow 后台白名单；空=不叠加后台限制
 */
public record ToolFilter(
        Set<String> globalDeny,
        Set<String> roleAllow,
        Set<String> roleDeny,
        Set<String> backgroundAllow) {

    public ToolFilter {
        globalDeny = normalize(globalDeny);
        roleAllow = normalize(roleAllow);
        roleDeny = normalize(roleDeny);
        backgroundAllow = normalize(backgroundAllow);
    }

    /** 空过滤（不限制）。 */
    public static ToolFilter none() {
        return new ToolFilter(Set.of(), Set.of(), Set.of(), Set.of());
    }

    /**
     * 计算最终可用工具集：结果 = (allTools − globalDeny − roleDeny)
     * ∩ (roleAllow 空 ? all : roleAllow) ∩ (backgroundAllow 空 ? all : backgroundAllow)。
     * 按 allTools 原顺序输出，比较统一小写（大小写不敏感）。
     */
    public Set<String> resolve(Set<String> allTools) {
        Set<String> result = new LinkedHashSet<>();
        for (String tool : allTools) {
            String t = tool.toLowerCase(Locale.ROOT);
            if (globalDeny.contains(t)) continue;
            if (roleDeny.contains(t)) continue;
            if (!roleAllow.isEmpty() && !roleAllow.contains(t)) continue;
            if (!backgroundAllow.isEmpty() && !backgroundAllow.contains(t)) continue;
            result.add(tool);
        }
        return Collections.unmodifiableSet(result);
    }

    /** 统一小写并剔除空白。 */
    private static Set<String> normalize(Set<String> in) {
        if (in == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (String s : in) {
            if (s != null && !s.isBlank()) {
                out.add(s.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }
}
