package dev.zencode.hook;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hook 配置加载与校验：从用户级与项目级 hooks.yaml 追加合并加载规则。
 * <p>用户级规则在前、项目级规则在后；文件不存在视为空贡献。
 * 集中校验事件名、动作类型、reject/async 约束与必填字段，错误定位到具体规则。</p>
 */
public final class HookLoader {

    public List<Hook> load(Path userFile, Path projectFile) {
        List<Hook> hooks = new ArrayList<>();
        loadInto(hooks, userFile);
        loadInto(hooks, projectFile);
        return List.copyOf(hooks);
    }

    private static void loadInto(List<Hook> target, Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        final String yaml;
        try {
            yaml = Files.readString(path);
        } catch (IOException e) {
            throw new HookConfigException("无法读取 Hook 配置文件: " + path);
        }
        Object parsed;
        try {
            parsed = new Load(LoadSettings.builder().build()).loadFromString(yaml);
        } catch (RuntimeException e) {
            throw new HookConfigException("Hook 配置文件格式无效: " + path);
        }
        if (parsed == null) {
            return;
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new HookConfigException("Hook 配置根节点必须是对象: " + path);
        }
        Object rawHooks = root.get("hooks");
        if (rawHooks == null) {
            return;
        }
        if (!(rawHooks instanceof List<?> list)) {
            throw new HookConfigException("hooks 必须是列表: " + path);
        }
        for (int i = 0; i < list.size(); i++) {
            target.add(parseHook(list.get(i), path + ": hooks[" + i + "]"));
        }
    }

    private static Hook parseHook(Object raw, String location) {
        Map<String, Object> m = asStringKeyMap(raw, "Hook 必须是对象: " + location);

        String eventText = requireString(m, "event", location);
        HookEvent event;
        try {
            event = HookEvent.fromString(eventText);
        } catch (IllegalArgumentException e) {
            throw new HookConfigException(location + ": " + e.getMessage());
        }

        Action action = parseAction(m.get("action"), location);
        ConditionGroup condition = parseCondition(m.get("if"), location);
        boolean once = bool(m.get("once"), "once", location);
        boolean async = bool(m.get("async"), "async", location);
        boolean reject = bool(m.get("reject"), "reject", location);

        if (reject && event != HookEvent.PRE_TOOL_USE) {
            throw new HookConfigException(location + ": reject 只能用于 pre_tool_use");
        }
        if (async && event == HookEvent.PRE_TOOL_USE) {
            throw new HookConfigException(location + ": pre_tool_use 不允许 async");
        }
        String id = m.get("id") instanceof String s && !s.isBlank() ? s : null;
        return new Hook(id, event, condition, action, once, async, reject);
    }

    private static Action parseAction(Object raw, String location) {
        Map<String, Object> m = asStringKeyMap(raw, location + ": action 必须是对象");
        String type = requireString(m, "type", location);
        return switch (type) {
            case "command" -> {
                String command = requireString(m, "command", location + " 的 command 动作");
                Duration timeout = parseTimeout(m.get("timeout_seconds"),
                        CommandAction.DEFAULT_TIMEOUT, location);
                yield new CommandAction(command, timeout);
            }
            case "prompt" -> new PromptAction(
                    requireString(m, "message", location + " 的 prompt 动作"));
            case "http" -> {
                String url = requireString(m, "url", location + " 的 http 动作");
                String method = m.get("method") instanceof String s ? s : "GET";
                Map<String, String> headers = stringMap(m.get("headers"), location);
                Duration timeout = parseTimeout(m.get("timeout_seconds"),
                        HttpAction.DEFAULT_TIMEOUT, location);
                yield new HttpAction(url, method, headers, timeout);
            }
            case "agent" -> new AgentAction(
                    requireString(m, "id", location + " 的 agent 动作"));
            default -> throw new HookConfigException(location + ": 未知 action 类型: " + type);
        };
    }

    private static Duration parseTimeout(Object raw, Duration def, String location) {
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number n && n.longValue() > 0) {
            return Duration.ofSeconds(n.longValue());
        }
        throw new HookConfigException(location + ": timeout_seconds 必须是正整数");
    }

    private static ConditionGroup parseCondition(Object raw, String location) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String expr)) {
            throw new HookConfigException(location + ": if 必须是字符串");
        }
        try {
            return ConditionParser.parse(expr);
        } catch (HookConfigException e) {
            throw new HookConfigException(location + ": " + e.getMessage());
        }
    }

    private static boolean bool(Object raw, String key, String location) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        throw new HookConfigException(location + ": " + key + " 必须是布尔值");
    }

    private static String requireString(Map<String, Object> m, String key, String location) {
        Object value = m.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new HookConfigException(location + ": 缺少必填字段 " + key);
        }
        return s;
    }

    private static Map<String, String> stringMap(Object raw, String location) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> item)) {
            throw new HookConfigException(location + ": headers 必须是键值对");
        }
        Map<String, String> out = new LinkedHashMap<>();
        item.forEach((k, v) -> {
            if (!(k instanceof String ks) || !(v instanceof String vs)) {
                throw new HookConfigException(location + ": headers 键值必须是字符串");
            }
            out.put(ks, vs);
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyMap(Object raw, String message) {
        if (!(raw instanceof Map<?, ?> item)) {
            throw new HookConfigException(message);
        }
        Map<String, Object> result = new HashMap<>();
        item.forEach((k, v) -> {
            if (!(k instanceof String ks)) {
                throw new HookConfigException(message + ": 键必须是字符串");
            }
            result.put(ks, v);
        });
        return result;
    }
}
