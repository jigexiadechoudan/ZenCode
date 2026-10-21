package dev.zencode.tool;

import java.util.Map;
import java.util.Objects;

/**
 * 工具的统一契约。每个工具具有名称、描述、JSON Schema 参数定义、分类、权限标记，
 * 并能接收结构化参数后返回统一的执行结果。
 */
public interface Tool {
    /** 模型调用使用的稳定工具名。 */
    String name();

    /** 面向模型的用途描述。 */
    String description();

    /** 工具分类：READ、WRITE、COMMAND。 */
    ToolCategory category();

    /** 权限标记：只读或需确认。 */
    ToolPermission permission();

    /**
     * 统一 JSON Schema，根结构包含对象类型、属性和必填字段。
     * 返回的 Map 符合 JSON Schema draft 格式。
     */
    Map<String, Object> schema();

    /**
     * 执行工具操作。
     * @param arguments 已解析的结构化参数（Map 形式）
     * @param context 执行上下文（工作目录、调用 ID、取消信号）
     * @return 统一工具执行结果
     */
    ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context);
}