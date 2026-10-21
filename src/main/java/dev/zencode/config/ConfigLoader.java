package dev.zencode.config;

import dev.zencode.cli.CliOptions;
import dev.zencode.memory.MemoryConfig;
import dev.zencode.permission.PermissionMode;
import dev.zencode.tui.Theme;
import dev.zencode.worktree.WorktreeConfig;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConfigLoader {
    private static final Set<String> PROVIDER_FIELDS = Set.of("name", "protocol", "model", "base_url", "api_key", "thinking");
    private static final Set<String> MCP_FIELDS = Set.of("name", "transport", "command", "args", "url", "env", "headers", "timeout");
    private static final Set<String> CONTEXT_FIELDS = Set.of(
            "window_tokens", "compact_ratio", "single_result_threshold",
            "round_total_threshold", "max_consecutive_summary_failures");
    private static final Set<String> MEMORY_FIELDS = Set.of(
            "enabled", "extract_interval_rounds", "memory_max_chars",
            "include_max_depth", "session_idle_hours", "session_archive");
    private static final Set<String> AGENT_FIELDS = Set.of("review_enabled", "background_timeout_seconds");
    private static final Set<String> WORKTREE_FIELDS = Set.of(
            "worktrees_root", "link_dirs", "copy_local_files", "copy_ignored",
            "stale_timeout_seconds", "cleanup_interval_seconds", "temp_prefix");
    private static final Set<String> TEAM_FIELDS = Set.of("coordinator");

    public LoadedConfig load(CliOptions options) {
        Path path = configPath(options);
        final String yaml;
        try {
            yaml = Files.readString(path);
        } catch (IOException e) {
            throw new ConfigException("无法读取配置文件: " + path);
        }
        return loadYaml(yaml, options);
    }

    public static Path configPath(CliOptions options) {
        return (options.configPath() != null ? options.configPath()
                : Path.of(System.getProperty("user.home"), ".zencode", "config.yaml")).toAbsolutePath().normalize();
    }

    /** Validate without writing a file or constructing providers / making network requests. */
    public LoadedConfig loadYaml(String yaml, CliOptions options) {
        Object parsed;
        try {
            parsed = new Load(LoadSettings.builder().build()).loadFromString(yaml);
        } catch (RuntimeException e) {
            throw new ConfigException("配置文件格式无效");
        }
        Map<String, Object> root = map(parsed, "配置根节点必须是对象");
        String defaultName = requiredString(root, "default", "缺少默认供应商");
        String selectedName = options.providerName() == null ? defaultName : options.providerName();
        String theme = stringValue(root.getOrDefault("theme", "workbench"), "theme 必须是字符串");
        if (!Theme.isSupported(theme)) throw new ConfigException("不支持的主题: " + theme);
        PermissionMode permissionMode = PermissionMode.fromString(
                stringValue(root.getOrDefault("permission_mode", "default"), "permission_mode 必须是字符串"));
        if (options.permissionMode() != null) {
            permissionMode = options.permissionMode();
        }
        List<Object> rawProviders = list(root.get("providers"), "providers 必须是列表");
        if (rawProviders.isEmpty()) throw new ConfigException("providers 不能为空");
        List<ProviderConfig> providers = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object raw : rawProviders) {
            Map<String, Object> item = map(raw, "供应商配置必须是对象");
            for (String key : item.keySet()) {
                if (!PROVIDER_FIELDS.contains(key)) throw new ConfigException("供应商配置包含未知字段: " + key);
            }
            String name = requiredString(item, "name", "供应商缺少 name");
            if (!names.add(name)) throw new ConfigException("供应商名称重复: " + name);
            String protocolText = requiredString(item, "protocol", "供应商缺少 protocol").toUpperCase();
            Protocol protocol;
            try {
                protocol = Protocol.valueOf(protocolText);
            } catch (IllegalArgumentException e) {
                throw new ConfigException("不支持的协议: " + protocolText);
            }
            String model = requiredString(item, "model", "供应商缺少 model");
            String baseUrl = requiredString(item, "base_url", "供应商缺少 base_url");
            URI uri;
            try {
                uri = URI.create(baseUrl);
                if (uri.getScheme() == null || uri.getHost() == null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new ConfigException("base_url 无效: " + baseUrl);
            }
            String apiKey = requiredString(item, "api_key", "供应商缺少 api_key");
            // Inactive entries retain environment references. They must not prevent repairing
            // or selecting a different provider in the UI; only selectedProvider is runtime-ready.
            if (name.equals(selectedName)) apiKey = resolveApiKey(apiKey);
            boolean thinking = booleanValue(item.getOrDefault("thinking", false), "thinking 必须是布尔值");
            providers.add(new ProviderConfig(name, protocol, model, uri, apiKey, thinking));
        }
        if (!names.contains(defaultName)) throw new ConfigException("默认供应商不存在: " + defaultName);
        ProviderConfig selected = providers.stream().filter(p -> p.name().equals(selectedName)).findFirst().orElse(null);
        if (selected == null) throw new ConfigException("指定供应商不存在: " + selectedName);
        List<McpServerConfig> mcpServers = parseMcpServers(root);
        ContextConfig context = parseContext(root);
        MemoryConfig memory = parseMemory(root);
        AgentConfig agents = parseAgents(root);
        WorktreeConfig worktree = parseWorktree(root);
        TeamConfig team = parseTeam(root);
        return new LoadedConfig(new AppConfig(defaultName, providers, theme, permissionMode, mcpServers, context, memory, agents), selected, worktree, team);
    }

    /** 解析可选的 context 段，缺省使用默认上下文配置。 */
    private static ContextConfig parseContext(Map<String, Object> root) {
        Object raw = root.get("context");
        if (raw == null) {
            return ContextConfig.defaults();
        }
        Map<String, Object> item = map(raw, "context 必须是对象");
        for (String key : item.keySet()) {
            if (!CONTEXT_FIELDS.contains(key)) throw new ConfigException("context 配置包含未知字段: " + key);
        }
        long windowTokens = item.containsKey("window_tokens")
                ? positiveLong(item, "window_tokens") : 200_000L;
        double ratio = item.containsKey("compact_ratio")
                ? positiveDouble(item, "compact_ratio") : 0.8;
        if (ratio > 1) {
            throw new ConfigException("compact_ratio 不能大于 1");
        }
        int single = item.containsKey("single_result_threshold")
                ? Math.toIntExact(positiveLong(item, "single_result_threshold")) : 4096;
        int roundTotal = item.containsKey("round_total_threshold")
                ? Math.toIntExact(positiveLong(item, "round_total_threshold")) : 32 * 1024;
        int maxFailures = item.containsKey("max_consecutive_summary_failures")
                ? Math.toIntExact(positiveLong(item, "max_consecutive_summary_failures")) : 3;
        return new ContextConfig(windowTokens, ratio, single, roundTotal, maxFailures);
    }

    /** 解析可选的 agents 段，缺省使用默认子 Agent 配置。 */
    private static AgentConfig parseAgents(Map<String, Object> root) {
        Object raw = root.get("agents");
        if (raw == null) {
            return AgentConfig.defaults();
        }
        Map<String, Object> item = map(raw, "agents 必须是对象");
        for (String key : item.keySet()) {
            if (!AGENT_FIELDS.contains(key)) throw new ConfigException("agents 配置包含未知字段: " + key);
        }
        boolean reviewEnabled = item.containsKey("review_enabled")
                ? booleanValue(item.get("review_enabled"), "review_enabled 必须是布尔值") : false;
        long timeout = item.containsKey("background_timeout_seconds")
                ? positiveLong(item, "background_timeout_seconds") : 300;
        return new AgentConfig(reviewEnabled, timeout);
    }

    /** 解析可选的 worktree 段，缺省使用默认配置。 */
    private static WorktreeConfig parseWorktree(Map<String, Object> root) {
        Object raw = root.get("worktree");
        if (raw == null) {
            return WorktreeConfig.defaults();
        }
        Map<String, Object> item = map(raw, "worktree 必须是对象");
        for (String key : item.keySet()) {
            if (!WORKTREE_FIELDS.contains(key)) throw new ConfigException("worktree 配置包含未知字段: " + key);
        }
        String worktreesRoot = item.containsKey("worktrees_root")
                ? stringValue(item.get("worktrees_root"), "worktrees_root 必须是字符串") : ".zencode/worktrees";
        List<String> linkDirs = item.containsKey("link_dirs") ? stringList(item, "link_dirs") : List.of();
        List<String> copyLocalFiles = item.containsKey("copy_local_files") ? stringList(item, "copy_local_files") : List.of();
        List<String> copyIgnored = item.containsKey("copy_ignored") ? stringList(item, "copy_ignored") : List.of();
        long staleTimeout = item.containsKey("stale_timeout_seconds")
                ? positiveLong(item, "stale_timeout_seconds") : 86400;
        long cleanupInterval = item.containsKey("cleanup_interval_seconds")
                ? positiveLong(item, "cleanup_interval_seconds") : 3600;
        String tempPrefix = item.containsKey("temp_prefix")
                ? stringValue(item.get("temp_prefix"), "temp_prefix 必须是字符串") : "wt-";
        return new WorktreeConfig(worktreesRoot, linkDirs, copyLocalFiles, copyIgnored,
                staleTimeout, cleanupInterval, tempPrefix);
    }

    /** 解析可选的 team 段，缺省使用默认团队配置（coordinator 关闭）。 */
    private static TeamConfig parseTeam(Map<String, Object> root) {
        Object raw = root.get("team");
        if (raw == null) {
            return TeamConfig.defaults();
        }
        Map<String, Object> item = map(raw, "team 必须是对象");
        for (String key : item.keySet()) {
            if (!TEAM_FIELDS.contains(key)) throw new ConfigException("team 配置包含未知字段: " + key);
        }
        Object coordinatorRaw = item.get("coordinator");
        boolean coordinatorEnabled = false;
        if (coordinatorRaw != null) {
            Map<String, Object> coordinator = map(coordinatorRaw, "coordinator 必须是对象");
            for (String key : coordinator.keySet()) {
                if (!Set.of("enabled").contains(key)) {
                    throw new ConfigException("coordinator 配置包含未知字段: " + key);
                }
            }
            coordinatorEnabled = coordinator.containsKey("enabled")
                    ? booleanValue(coordinator.get("enabled"), "coordinator.enabled 必须是布尔值") : false;
        }
        return new TeamConfig(coordinatorEnabled);
    }

    /** 解析可选的 memory 段，缺省使用默认记忆配置。 */
    private static MemoryConfig parseMemory(Map<String, Object> root) {
        Object raw = root.get("memory");
        if (raw == null) {
            return MemoryConfig.defaults();
        }
        Map<String, Object> item = map(raw, "memory 必须是对象");
        for (String key : item.keySet()) {
            if (!MEMORY_FIELDS.contains(key)) throw new ConfigException("memory 配置包含未知字段: " + key);
        }
        boolean enabled = item.containsKey("enabled")
                ? booleanValue(item.get("enabled"), "enabled 必须是布尔值") : true;
        int extractIntervalRounds = item.containsKey("extract_interval_rounds")
                ? Math.toIntExact(positiveLong(item, "extract_interval_rounds")) : 5;
        int memoryMaxChars = item.containsKey("memory_max_chars")
                ? Math.toIntExact(positiveLong(item, "memory_max_chars")) : 16384;
        int includeMaxDepth = item.containsKey("include_max_depth")
                ? Math.toIntExact(positiveLong(item, "include_max_depth")) : 3;
        int sessionIdleHours = item.containsKey("session_idle_hours")
                ? Math.toIntExact(positiveLong(item, "session_idle_hours")) : 24;
        boolean sessionArchive = item.containsKey("session_archive")
                ? booleanValue(item.get("session_archive"), "session_archive 必须是布尔值") : true;
        return new MemoryConfig(enabled, extractIntervalRounds, memoryMaxChars, includeMaxDepth, sessionIdleHours, sessionArchive);
    }

    /** 解析可选的 mcp_servers 列表，缺省为空。 */
    private static List<McpServerConfig> parseMcpServers(Map<String, Object> root) {
        Object rawMcp = root.get("mcp_servers");
        if (rawMcp == null) {
            return List.of();
        }
        List<Object> rawList = list(rawMcp, "mcp_servers 必须是列表");
        List<McpServerConfig> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object raw : rawList) {
            Map<String, Object> item = map(raw, "MCP server 配置必须是对象");
            for (String key : item.keySet()) {
                if (!MCP_FIELDS.contains(key)) throw new ConfigException("MCP server 配置包含未知字段: " + key);
            }
            String name = requiredString(item, "name", "MCP server 缺少 name");
            if (!names.add(name)) throw new ConfigException("MCP server 名称重复: " + name);
            String transport = requiredString(item, "transport", "MCP server 缺少 transport");
            if (!"stdio".equalsIgnoreCase(transport) && !"http".equalsIgnoreCase(transport)) {
                throw new ConfigException("不支持的 MCP transport: " + transport);
            }
            String command = item.containsKey("command")
                    ? requiredString(item, "command", "command 必须是字符串") : null;
            List<String> args = item.containsKey("args") ? stringList(item, "args") : null;
            URI url = item.containsKey("url") ? parseUrl(requiredString(item, "url", "url 必须是字符串")) : null;
            Map<String, String> env = stringMap(item, "env");
            Map<String, String> headers = stringMap(item, "headers");
            long timeout = item.containsKey("timeout") ? positiveLong(item, "timeout")
                    : McpServerConfig.defaultTimeoutMillis();
            if ("stdio".equalsIgnoreCase(transport)) {
                if (command == null || command.isBlank()) {
                    throw new ConfigException("stdio MCP server 必须提供 command: " + name);
                }
            } else {
                if (url == null) {
                    throw new ConfigException("http MCP server 必须提供 url: " + name);
                }
            }
            result.add(new McpServerConfig(name, transport.toLowerCase(), command, args, url, env, headers, timeout));
        }
        return List.copyOf(result);
    }

    private static URI parseUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException e) {
            throw new ConfigException("url 无效: " + value);
        }
    }

    private static List<String> stringList(Map<String, Object> item, String key) {
        Object value = item.get(key);
        if (value == null) return List.of();
        if (!(value instanceof List<?> raw)) throw new ConfigException(key + " 必须是字符串列表");
        List<String> out = new ArrayList<>();
        for (Object element : raw) {
            if (!(element instanceof String s)) throw new ConfigException(key + " 的元素必须是字符串");
            out.add(s);
        }
        return out;
    }

    /** 解析字符串键值对，值支持 ${ENV} 引用展开。 */
    private static Map<String, String> stringMap(Map<String, Object> item, String key) {
        Object value = item.get(key);
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> raw)) throw new ConfigException(key + " 必须是键值对");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String k)) throw new ConfigException(key + " 的键必须是字符串");
            if (!(entry.getValue() instanceof String v)) throw new ConfigException(key + " 的值必须是字符串: " + k);
            out.put(k, resolveEnvRef(v, key + "." + k));
        }
        return out;
    }

    private static long positiveLong(Map<String, Object> item, String key) {
        Object value = item.get(key);
        if (value instanceof Number n && n.longValue() > 0) {
            return n.longValue();
        }
        throw new ConfigException(key + " 必须是正整数");
    }

    private static double positiveDouble(Map<String, Object> item, String key) {
        Object value = item.get(key);
        if (value instanceof Number n && n.doubleValue() > 0) {
            return n.doubleValue();
        }
        throw new ConfigException(key + " 必须是正数");
    }

    private static String resolveApiKey(String value) {
        return resolveEnvRef(value, "api_key");
    }

    /** 展开 ${ENV_NAME} 引用；非引用或引用为空时原样返回。 */
    private static String resolveEnvRef(String value, String field) {
        if (value.startsWith("${") && value.endsWith("}") && value.length() > 3) {
            String envName = value.substring(2, value.length() - 1);
            String resolved = System.getenv(envName);
            if (resolved == null || resolved.isBlank()) throw new ConfigException("环境变量未设置: " + envName + "（" + field + "）");
            return resolved;
        }
        return value;
    }

    private static String requiredString(Map<String, Object> map, String key, String message) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new ConfigException(message);
        return text;
    }

    private static String stringValue(Object value, String message) {
        if (!(value instanceof String text) || text.isBlank()) throw new ConfigException(message);
        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String message) {
        if (!(value instanceof Map<?, ?> raw)) throw new ConfigException(message);
        Map<String, Object> result = new HashMap<>();
        raw.forEach((key, item) -> {
            if (!(key instanceof String name)) throw new ConfigException("配置键必须是字符串");
            result.put(name, item);
        });
        return result;
    }

    private static List<Object> list(Object value, String message) {
        if (!(value instanceof List<?> raw)) throw new ConfigException(message);
        return new ArrayList<>(raw);
    }

    private static boolean booleanValue(Object value, String message) {
        if (!(value instanceof Boolean result)) throw new ConfigException(message);
        return result;
    }

    public record LoadedConfig(AppConfig appConfig, ProviderConfig selectedProvider, WorktreeConfig worktree, TeamConfig team) {}
}
