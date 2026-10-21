package dev.zencode.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 命令注册中心：注册、查重、查找、列举、补全。
 * <p>
 * 维护从命令名/别名（小写规范化）到 {@link CommandSpec} 的索引。
 * 注册时若命令名或别名与既有索引冲突则抛异常。
 * </p>
 */
public final class CommandRegistry {

    private final Map<String, CommandSpec> index = new LinkedHashMap<>();
    private final List<CommandSpec> registered = new ArrayList<>();

    /**
     * 注册命令。
     *
     * @param spec 命令元数据
     * @throws IllegalArgumentException 命令名或别名与既有条目冲突
     */
    public void register(CommandSpec spec) {
        if (spec == null) throw new IllegalArgumentException("命令不能为空");
        String nameKey = normalized(spec.command());
        if (index.containsKey(nameKey)) {
            throw new IllegalArgumentException("命令名冲突: " + spec.command());
        }
        for (String alias : spec.aliases()) {
            String key = normalized(alias);
            if (index.containsKey(key)) {
                throw new IllegalArgumentException("别名冲突: " + alias + " (" + spec.command() + ")");
            }
        }
        index.put(nameKey, spec);
        for (String alias : spec.aliases()) {
            index.put(normalized(alias), spec);
        }
        registered.add(spec);
    }

    /**
     * 按名称或别名精确查找。
     *
     * @param name 命令名或别名（可带 / 前缀）
     * @return 命中的命令
     */
    public Optional<CommandSpec> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(index.get(normalized(name)));
    }

    /**
     * 移除命令：按名称或别名定位后，同时清掉命令名与全部别名键及注册列表项。
     *
     * @param name 命令名或别名（可带 / 前缀）
     * @return 找到并移除返回 true，否则 false
     */
    public boolean remove(String name) {
        if (name == null) return false;
        String key = normalized(name);
        CommandSpec spec = index.get(key);
        if (spec == null) return false;
        index.remove(normalized(spec.command()));
        for (String alias : spec.aliases()) {
            index.remove(normalized(alias));
        }
        registered.remove(spec);
        return true;
    }

    /** 全部非隐藏命令（用于列表展示）。 */
    public List<CommandSpec> list() {
        List<CommandSpec> result = new ArrayList<>();
        for (CommandSpec spec : registered) {
            if (!spec.hidden()) result.add(spec);
        }
        return result;
    }

    /**
     * 按前缀过滤命令（名称与别名都参与），返回去重后的命令列表。
     *
     * @param prefix 输入前缀（可含 / 开头或仅 /）
     */
    public List<CommandSpec> complete(String prefix) {
        String like = prefix == null ? "" : normalized(prefix);
        List<CommandSpec> result = new ArrayList<>();
        for (CommandSpec spec : registered) {
            if (starts(spec.command(), like) || spec.aliases().stream().anyMatch(a -> starts(a, like))) {
                if (!result.contains(spec)) result.add(spec);
            }
        }
        return result;
    }

    /** 已注册命令总数（含隐藏）。 */
    public int size() {
        return registered.size();
    }

    private static boolean starts(String value, String like) {
        return normalized(value).startsWith(like);
    }

    /** 规范化：转小写并去掉开头的 /，使 "plan" 与 "/plan"、"/PLAN" 等价。 */
    private static String normalized(String value) {
        String s = value.toLowerCase(Locale.ROOT);
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }
}