package dev.zencode.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zencode.mcp.client.McpClient;
import dev.zencode.mcp.jsonrpc.JsonRpcCodec;
import dev.zencode.mcp.jsonrpc.JsonRpcException;
import dev.zencode.mcp.jsonrpc.JsonRpcMessage;
import dev.zencode.mcp.transport.TransportException;
import dev.zencode.tool.Tool;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 适配层：把 MCP 工具包装成 ZenCode 内部 {@link Tool} 契约，Agent 调用时与内置工具无感。
 * <p>
 * 包装后的工具名形如「server名:工具名」，与内置工具及其他 Server 的工具不冲突；
 * 统一标记为 COMMAND 分类 + 需确认权限，接入既有权限拦截链。
 * 执行时把结构化参数转为 tools/call 请求，返回结果映射为内部统一工具结果。
 * </p>
 */
public final class McpToolWrapper implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpClient client;
    private final McpToolInfo info;
    private final String serverName;

    public McpToolWrapper(McpClient client, McpToolInfo info, String serverName) {
        this.client = Objects.requireNonNull(client, "客户端不能为空");
        this.info = Objects.requireNonNull(info, "工具信息不能为空");
        this.serverName = Objects.requireNonNull(serverName, "Server 名不能为空");
    }

    @Override
    public String name() {
        return serverName + ":" + info.name();
    }

    @Override
    public String description() {
        return info.description();
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.REQUIRES_CONFIRMATION;
    }

    @Override
    public Map<String, Object> schema() {
        return info.inputSchema();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        Objects.requireNonNull(arguments, "参数不能为空");
        Objects.requireNonNull(context, "执行上下文不能为空");
        if (context.isCancelled()) {
            return ToolResult.cancelled(context.callId(), name(), "调用已取消");
        }
        try {
            JsonRpcMessage.Response response = client.callTool(info.name(), arguments);
            if (response.error() != null) {
                return ToolResult.error(context.callId(), name(),
                        "MCP 工具错误: " + response.error().message());
            }
            Object result = response.result();
            if (result instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("isError"))) {
                return ToolResult.error(context.callId(), name(),
                        "MCP 工具返回错误: " + extractText(map));
            }
            return ToolResult.success(context.callId(), name(), formatResult(result));
        } catch (TransportException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.startsWith("等待响应超时")) {
                return ToolResult.timeout(context.callId(), name(), message);
            }
            return ToolResult.error(context.callId(), name(), "MCP 调用失败: " + message);
        } catch (JsonRpcException e) {
            return ToolResult.error(context.callId(), name(), "MCP 协议错误: " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.error(context.callId(), name(), "MCP 调用失败: " + e.getMessage());
        }
    }

    /** 提取 MCP 文本内容（content 数组中 type=text 的 text 字段）。 */
    private static String extractText(Map<?, ?> map) {
        Object content = map.get("content");
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && "text".equals(m.get("type"))) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(m.get("text"));
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        return toJson(map);
    }

    /** 把 MCP 调用结果转为可读文本：优先 text content，否则序列化为 JSON。 */
    private static String formatResult(Object result) {
        if (result instanceof Map<?, ?> map) {
            String text = extractText(map);
            return text.isEmpty() ? toJson(map) : text;
        }
        return toJson(result);
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
