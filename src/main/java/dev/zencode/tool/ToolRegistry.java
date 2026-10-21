package dev.zencode.tool;

import dev.zencode.config.Protocol;
import dev.zencode.tool.impl.*;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具注册中心，集中管理工具注册、启用、禁用、查找和模型 API Schema 输出。
 * <p>
 * 使用插入顺序稳定的映射保存工具和独立启用状态。
 * 默认工厂注册六个工具并全部启用。
 * Schema 输出只包含启用工具；执行入口再次检查启用状态。
 * 注册顺序稳定，同名注册的后者替换前者。
 * </p>
 */
public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Set<String> enabledTools = new LinkedHashSet<>();

    /** 创建一个默认注册中心，注册六个核心工具并全部启用。 */
    public static ToolRegistry createDefault(Path workspaceRoot) {
        return createDefault(new WorkspacePathResolver(workspaceRoot));
    }

    public static ToolRegistry createDefault(WorkspacePathResolver resolver) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(resolver));
        registry.register(new WriteFileTool(resolver));
        registry.register(new EditFileTool(resolver));
        registry.register(new BashTool(resolver));
        registry.register(new GlobTool(resolver));
        registry.register(new GrepTool(resolver));
        return registry;
    }

    /** 子运行的内置工具绑定独立目录；扩展工具与启用状态保留。 */
    public ToolRegistry forWorkspace(Path directory) {
        WorkspacePathResolver resolver = new WorkspacePathResolver(directory);
        ToolRegistry copy = new ToolRegistry();
        for (Tool tool : tools.values()) {
            Tool rebound = switch (tool) {
                case ReadFileTool ignored -> new ReadFileTool(resolver);
                case WriteFileTool ignored -> new WriteFileTool(resolver);
                case EditFileTool ignored -> new EditFileTool(resolver);
                case BashTool ignored -> new BashTool(resolver);
                case GlobTool ignored -> new GlobTool(resolver);
                case GrepTool ignored -> new GrepTool(resolver);
                default -> tool;
            };
            copy.register(rebound);
            if (!isEnabled(tool.name())) copy.disable(tool.name());
        }
        return copy;
    }

    /** 注册工具。同名后注册工具覆盖前者并保持预期顺序。 */
    public void register(Tool tool) {
        Objects.requireNonNull(tool, "工具不能为空");
        String name = tool.name();
        boolean isNew = !tools.containsKey(name);
        tools.put(name, tool);
        if (isNew) {
            enabledTools.add(name);
        }
    }

    /** 启用工具。 */
    public void enable(String name) {
        if (!tools.containsKey(name)) {
            throw new IllegalArgumentException("未知工具: " + name);
        }
        enabledTools.add(name);
    }

    /** 禁用工具。禁用后不出现在模型工具列表中，收到对应调用时也拒绝执行。 */
    public void disable(String name) {
        if (!tools.containsKey(name)) {
            throw new IllegalArgumentException("未知工具: " + name);
        }
        enabledTools.remove(name);
    }

    /** 检查工具是否启用。 */
    public boolean isEnabled(String name) {
        return enabledTools.contains(name);
    }

    /** 查找工具。返回 Optional 以处理不存在的情况。 */
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 注销工具：从注册表与启用集合中移除；不存在时抛异常（与 disable 一致）。 */
    public void unregister(String name) {
        if (!tools.containsKey(name)) {
            throw new IllegalArgumentException("未知工具: " + name);
        }
        tools.remove(name);
        enabledTools.remove(name);
    }

    /** 返回所有已注册工具的名称列表（按注册顺序）。 */
    public List<String> toolNames() {
        return List.copyOf(tools.keySet());
    }

    /** 返回所有已启用工具的名称列表（按注册顺序）。 */
    public List<String> enabledToolNames() {
        return tools.keySet().stream()
                .filter(enabledTools::contains)
                .collect(Collectors.toUnmodifiableList());
    }

    /** 获取工具 Schema 列表，按 Provider 协议转换。只包含已启用工具，按注册顺序。 */
    public List<Map<String, Object>> schemas(Protocol protocol) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Tool> entry : tools.entrySet()) {
            if (!enabledTools.contains(entry.getKey())) continue;
            Tool tool = entry.getValue();
            result.add(convertSchema(tool, protocol));
        }
        return List.copyOf(result);
    }

    /**
     * 执行工具。检查启用状态，禁用/未知工具返回结构化结果而不执行。
     *
     * @return 工具执行结果，如果工具不存在则返回未知工具结果
     */
    public ToolResult execute(String name, Map<String, Object> arguments, ToolExecutionContext context) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return ToolResult.unknownTool(context.callId(), name);
        }
        if (!enabledTools.contains(name)) {
            return ToolResult.disabled(context.callId(), name);
        }
        return tool.execute(arguments, context);
    }

    /**
     * 将统一工具 Schema 转换为 Provider 协议格式。
     */
    private static Map<String, Object> convertSchema(Tool tool, Protocol protocol) {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> toolSchema = tool.schema();

        switch (protocol) {
            case ANTHROPIC -> {
                schema.put("name", tool.name());
                schema.put("description", tool.description());
                // Anthropic 使用 input_schema
                Map<String, Object> inputSchema = new LinkedHashMap<>(toolSchema);
                schema.put("input_schema", inputSchema);
            }
            case OPENAI -> {
                // OpenAI 使用 function 结构
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.put("parameters", new LinkedHashMap<>(toolSchema));
                schema.put("type", "function");
                schema.put("function", function);
            }
        }
        return schema;
    }
}
