package dev.zencode.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 抽象工具基类，统一保存元信息、参数校验和异常转换。
 * <p>
 * 具体工具只需实现 {@link #executeInternal(Map, ToolExecutionContext)}，
 * 在合法参数下执行核心操作即可。
 * </p>
 */
public abstract class BaseTool implements Tool {
    private final String name;
    private final String description;
    private final ToolCategory category;
    private final ToolPermission permission;
    private final Map<String, Object> schema;
    protected final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param schema 统一 JSON Schema，根结构为对象类型
     */
    protected BaseTool(String name, String description, ToolCategory category,
                       ToolPermission permission, Map<String, Object> schema) {
        this.name = Objects.requireNonNull(name, "工具名称不能为空");
        this.description = Objects.requireNonNull(description, "工具描述不能为空");
        this.category = Objects.requireNonNull(category, "工具分类不能为空");
        this.permission = Objects.requireNonNull(permission, "权限标记不能为空");
        this.schema = schema == null ? Map.of() : Collections.unmodifiableMap(schema);
    }

    @Override
    public final String name() { return name; }

    @Override
    public final String description() { return description; }

    @Override
    public final ToolCategory category() { return category; }

    @Override
    public final ToolPermission permission() { return permission; }

    @Override
    public final Map<String, Object> schema() { return schema; }

    /**
     * 入口方法：统一捕获异常并转为安全失败结果。
     * 子类实现 {@link #executeInternal(Map, ToolExecutionContext)} 即可。
     */
    @Override
    public final ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        Objects.requireNonNull(arguments, "参数不能为空");
        Objects.requireNonNull(context, "执行上下文不能为空");
        try {
            return executeInternal(arguments, context);
        } catch (ToolExecutionException e) {
            return ToolResult.error(context.callId(), name, e.getMessage());
        } catch (Exception e) {
            return ToolResult.error(context.callId(), name, "执行失败: " + e.getMessage());
        }
    }

    /**
     * 子类实现合法参数下的核心操作。
     * 抛出 {@link ToolExecutionException} 返回安全失败结果。
     */
    protected abstract ToolResult executeInternal(Map<String, Object> arguments,
                                                   ToolExecutionContext context);

    // ---- 参数辅助方法 ----

    /** 读取必填字符串参数。缺失或类型错误时抛出。允许空字符串。 */
    protected String requiredString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof String s) return s;
        throw new ToolExecutionException("缺少必填参数 '" + key + "'，需要字符串类型");
    }

    /** 读取可选字符串参数，不存在时返回默认值。 */
    protected String optionalString(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        return value instanceof String s ? s : defaultValue;
    }

    /** 读取必填整数参数。缺失或类型错误时抛出。 */
    protected int requiredInt(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) {
                throw new ToolExecutionException("参数 '" + key + "' 不是有效的整数");
            }
        }
        throw new ToolExecutionException("缺少必填参数 '" + key + "'，需要整数类型");
    }

    /** 读取可选整数参数，不存在时返回默认值。 */
    protected int optionalInt(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    /** 读取必填布尔参数。 */
    protected boolean requiredBoolean(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Boolean b) return b;
        throw new ToolExecutionException("缺少必填参数 '" + key + "'，需要布尔类型");
    }

    /** 读取可选布尔参数。 */
    protected boolean optionalBoolean(Map<String, Object> args, String key, boolean defaultValue) {
        Object value = args.get(key);
        return value instanceof Boolean b ? b : defaultValue;
    }

    /** 将参数 JSON 字符串解析为 Map。 */
    protected Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new ToolExecutionException("参数 JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * 构建标准 JSON Schema 对象。
     * @param properties 属性名到 Schema 子映射的对应
     * @param required 必填字段列表
     * @return 符合 JSON Schema draft 的 Map
     */
    protected static Map<String, Object> jsonSchema(Map<String, Map<String, Object>> properties,
                                                     List<String> required) {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        if (properties != null) props.putAll(properties);
        schema.put("properties", props);
        if (required != null && !required.isEmpty()) {
            schema.put("required", List.copyOf(required));
        }
        return schema;
    }

    /** 创建字符串属性 Schema。 */
    protected static Map<String, Object> stringProp(String description) {
        Map<String, Object> prop = new java.util.LinkedHashMap<>();
        prop.put("type", "string");
        if (description != null) prop.put("description", description);
        return prop;
    }

    /** 创建整数属性 Schema。 */
    protected static Map<String, Object> integerProp(String description) {
        Map<String, Object> prop = new java.util.LinkedHashMap<>();
        prop.put("type", "integer");
        if (description != null) prop.put("description", description);
        return prop;
    }

    /** 创建布尔属性 Schema。 */
    protected static Map<String, Object> booleanProp(String description) {
        Map<String, Object> prop = new java.util.LinkedHashMap<>();
        prop.put("type", "boolean");
        if (description != null) prop.put("description", description);
        return prop;
    }

    /** 工具执行期间的可控异常，自动转为安全失败结果。 */
    protected static final class ToolExecutionException extends RuntimeException {
        public ToolExecutionException(String message) {
            super(message);
        }
    }
}