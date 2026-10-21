package dev.zencode.mcp.client;

import dev.zencode.mcp.McpToolInfo;
import dev.zencode.mcp.jsonrpc.JsonRpcCodec;
import dev.zencode.mcp.jsonrpc.JsonRpcErrorCode;
import dev.zencode.mcp.jsonrpc.JsonRpcException;
import dev.zencode.mcp.jsonrpc.JsonRpcMessage;
import dev.zencode.mcp.transport.Transport;
import dev.zencode.mcp.transport.TransportException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 客户端：对一个 Server 的一次会话，分三阶段——初始化握手（initialize）、
 * 工具发现（tools/list）、工具调用（tools/call）。
 * <p>
 * 出站请求分配自增 id 并登记到待匹配表；入站消息按 id 关联到对应请求完成 future。
 * 通知（无 id）不参与匹配；无匹配请求的响应丢弃并记录日志，不抛异常。
 * 能力声明不包含 resources/prompts/sampling（spec 边界）。
 * </p>
 */
public final class McpClient implements AutoCloseable {
    public static final String PROTOCOL_VERSION_2025_03_26 = "2025-03-26";
    public static final String PROTOCOL_VERSION_2024_11_05 = "2024-11-05";

    private static final Set<String> SUPPORTED_VERSIONS =
            Set.of(PROTOCOL_VERSION_2025_03_26, PROTOCOL_VERSION_2024_11_05);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String serverName;
    private final Transport transport;
    private final Duration timeout;
    private final AtomicLong idGen = new AtomicLong();
    private final ConcurrentHashMap<Object, CompletableFuture<JsonRpcMessage.Response>> pending =
            new ConcurrentHashMap<>();
    private final List<McpToolInfo> discoveredTools = new ArrayList<>();

    public McpClient(String serverName, Transport transport, Duration timeout) {
        this.serverName = Objects.requireNonNull(serverName, "Server 名不能为空");
        this.transport = Objects.requireNonNull(transport, "传输不能为空");
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        transport.setMessageHandler(this::onMessage);
    }

    /** 初始化握手：发送 initialize 请求，校验协议版本后发送 notifications/initialized。 */
    public void initialize(Map<String, Object> clientInfo, Map<String, Object> capabilities) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION_2025_03_26);
        if (clientInfo != null) {
            params.put("clientInfo", clientInfo);
        }
        params.put("capabilities", capabilities == null ? Map.of() : capabilities);
        JsonRpcMessage.Response response = request("initialize", params);
        Object result = response.result();
        if (!(result instanceof Map<?, ?> map)) {
            throw new JsonRpcException(JsonRpcErrorCode.INTERNAL_ERROR, "握手响应缺少 result");
        }
        Object version = map.get("protocolVersion");
        if (!(version instanceof String v) || !SUPPORTED_VERSIONS.contains(v)) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_REQUEST,
                    "服务器协议版本不受支持: " + version);
        }
        transport.send(JsonRpcCodec.encode(
                new JsonRpcMessage.Notification("notifications/initialized", Map.of())));
    }

    /** 工具发现：tools/list，返回解析出的工具元信息。 */
    public List<McpToolInfo> listTools() {
        JsonRpcMessage.Response response = request("tools/list", Map.of());
        Object result = response.result();
        if (!(result instanceof Map<?, ?> map)) {
            throw new JsonRpcException(JsonRpcErrorCode.INTERNAL_ERROR, "tools/list 响应缺少 result");
        }
        Object toolsValue = map.get("tools");
        if (!(toolsValue instanceof List<?> list)) {
            throw new JsonRpcException(JsonRpcErrorCode.INTERNAL_ERROR, "tools/list 响应缺少 tools 列表");
        }
        List<McpToolInfo> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object name = m.get("name");
            Object description = m.get("description");
            Object inputSchema = m.get("inputSchema");
            if (!(name instanceof String n) || n.isBlank()) {
                continue;
            }
            String desc = description instanceof String d ? d : "";
            Map<String, Object> schema = asSchemaMap(inputSchema);
            out.add(new McpToolInfo(n, desc, schema));
        }
        discoveredTools.clear();
        discoveredTools.addAll(out);
        return List.copyOf(out);
    }

    /** 工具调用：tools/call，返回原始 JSON-RPC 响应。 */
    public JsonRpcMessage.Response callTool(String name, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        if (arguments != null && !arguments.isEmpty()) {
            params.put("arguments", arguments);
        }
        return request("tools/call", params);
    }

    /** 本会话已发现的工具（供查询）。 */
    public List<McpToolInfo> discoveredTools() {
        return List.copyOf(discoveredTools);
    }

    /**
     * 发送一个请求并阻塞等待对应响应，按 id 异步匹配。
     *
     * @throws TransportException 发送失败、等待超时或执行异常
     */
    private JsonRpcMessage.Response request(String method, Map<String, Object> params) {
        Object id = idGen.incrementAndGet();
        CompletableFuture<JsonRpcMessage.Response> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            transport.send(JsonRpcCodec.encode(new JsonRpcMessage.Request(id, method, params)));
        } catch (RuntimeException e) {
            pending.remove(id);
            throw e;
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new TransportException("等待响应超时（" + timeout.toSeconds() + "s）: " + method);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            throw new TransportException("等待响应被中断: " + method, e);
        } catch (ExecutionException e) {
            pending.remove(id);
            Throwable cause = e.getCause();
            throw new TransportException("请求执行失败: "
                    + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        }
    }

    /** 入站消息处理器：按 id 关联响应，通知与未匹配响应忽略。 */
    private void onMessage(String json) {
        try {
            JsonRpcMessage message = JsonRpcCodec.decode(json);
            if (message instanceof JsonRpcMessage.Response response) {
                Object id = response.id();
                CompletableFuture<JsonRpcMessage.Response> future = pending.remove(id);
                if (future != null) {
                    future.complete(response);
                } else {
                    System.err.println("[mcp-client] 未匹配的响应 id=" + id + " 已丢弃");
                }
            }
            // 通知（无 id）不参与匹配，直接忽略
        } catch (JsonRpcException e) {
            System.err.println("[mcp-client] 入站消息解析失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asSchemaMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @Override
    public void close() {
        for (CompletableFuture<JsonRpcMessage.Response> future : pending.values()) {
            future.completeExceptionally(new TransportException("客户端已关闭"));
        }
        pending.clear();
        transport.close();
    }
}
