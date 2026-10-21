package dev.zencode.mcp.client;

import dev.zencode.mcp.McpToolInfo;
import dev.zencode.mcp.jsonrpc.JsonRpcCodec;
import dev.zencode.mcp.jsonrpc.JsonRpcException;
import dev.zencode.mcp.jsonrpc.JsonRpcMessage;
import dev.zencode.mcp.testutil.FakeTransport;
import dev.zencode.mcp.transport.TransportException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * McpClient 测试：用内存假 Transport 验证握手、发现、调用、请求-响应匹配与超时。
 */
class McpClientTest {

    @Test
    void initializeHandshake() throws Exception {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("srv", transport, Duration.ofSeconds(5));
        CompletableFuture<Void> init = CompletableFuture.runAsync(() -> client.initialize(
                Map.of("name", "zencode", "version", "0.1.0"), Map.of("tools", Map.of())));

        JsonRpcMessage.Request req = takeRequest(transport);
        assertEquals("initialize", req.method());
        Map<?, ?> params = (Map<?, ?>) req.params();
        assertEquals("2025-03-26", params.get("protocolVersion"));
        Map<?, ?> capabilities = (Map<?, ?>) params.get("capabilities");
        assertFalse(capabilities.containsKey("resources"), "能力声明不应包含 resources");
        assertFalse(capabilities.containsKey("prompts"), "能力声明不应包含 prompts");
        assertFalse(capabilities.containsKey("sampling"), "能力声明不应包含 sampling");

        transport.reply(JsonRpcCodec.encode(JsonRpcMessage.Response.result(req.id(),
                Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(), "serverInfo", Map.of()))));
        init.get(5, TimeUnit.SECONDS);

        String second = transport.sentAll.get(1);
        JsonRpcMessage.Notification notif =
                assertInstanceOf(JsonRpcMessage.Notification.class, JsonRpcCodec.decode(second));
        assertEquals("notifications/initialized", notif.method());
    }

    @Test
    void initializeRejectsUnsupportedVersion() throws Exception {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("srv", transport, Duration.ofSeconds(5));
        CompletableFuture<Void> init = CompletableFuture.runAsync(() -> client.initialize(Map.of(), Map.of()));

        JsonRpcMessage.Request req = takeRequest(transport);
        transport.reply(JsonRpcCodec.encode(JsonRpcMessage.Response.result(req.id(),
                Map.of("protocolVersion", "2019-01-01"))));
        java.util.concurrent.ExecutionException ex =
                assertThrows(java.util.concurrent.ExecutionException.class, () -> init.get(5, TimeUnit.SECONDS));
        assertInstanceOf(JsonRpcException.class, ex.getCause());
    }

    @Test
    void listToolsParses() throws Exception {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("srv", transport, Duration.ofSeconds(5));
        CompletableFuture<List<McpToolInfo>> future = CompletableFuture.supplyAsync(client::listTools);

        JsonRpcMessage.Request req = takeRequest(transport);
        assertEquals("tools/list", req.method());
        List<Map<String, Object>> tools = List.of(
                Map.of("name", "list_issues", "description", "列出 issue",
                        "inputSchema", Map.of("type", "object")),
                Map.of("name", "get_issue"));
        transport.reply(JsonRpcCodec.encode(JsonRpcMessage.Response.result(req.id(),
                Map.of("tools", tools))));

        List<McpToolInfo> result = future.get(5, TimeUnit.SECONDS);
        assertEquals(2, result.size());
        assertEquals("list_issues", result.get(0).name());
        assertEquals("列出 issue", result.get(0).description());
        assertEquals("object", result.get(0).inputSchema().get("type"));
        assertEquals("", result.get(1).description());
    }

    @Test
    void callToolRoundTrip() throws Exception {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("srv", transport, Duration.ofSeconds(5));
        CompletableFuture<JsonRpcMessage.Response> future =
                CompletableFuture.supplyAsync(() -> client.callTool("list_issues", Map.of("repo", "a/b")));

        JsonRpcMessage.Request req = takeRequest(transport);
        assertEquals("tools/call", req.method());
        Map<?, ?> params = (Map<?, ?>) req.params();
        assertEquals("list_issues", params.get("name"));
        assertEquals(Map.of("repo", "a/b"), params.get("arguments"));

        transport.reply(JsonRpcCodec.encode(JsonRpcMessage.Response.result(req.id(),
                Map.of("content", List.of(Map.of("type", "text", "text", "issue 1"))))));
        JsonRpcMessage.Response resp = future.get(5, TimeUnit.SECONDS);
        Map<?, ?> result = (Map<?, ?>) resp.result();
        assertEquals(List.of(Map.of("type", "text", "text", "issue 1")), result.get("content"));
    }

    @Test
    void requestTimeoutThrows() {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("srv", transport, Duration.ofMillis(300));
        assertThrows(TransportException.class, () -> client.callTool("x", Map.of()));
    }

    @Test
    void unmatchedResponseIgnored() {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("srv", transport, Duration.ofSeconds(5));
        transport.reply(JsonRpcCodec.encode(JsonRpcMessage.Response.result(999L, Map.of("ignored", true))));
        // 无匹配 id 的响应被丢弃，不抛异常
    }

    @Test
    void notificationIgnored() {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("srv", transport, Duration.ofSeconds(5));
        transport.reply(JsonRpcCodec.encode(
                new JsonRpcMessage.Notification("notifications/tools/list_changed", Map.of())));
        // 通知无 id，不参与匹配，不抛异常
    }

    private static JsonRpcMessage.Request takeRequest(FakeTransport transport) throws Exception {
        String json = transport.requestQueue.poll(5, TimeUnit.SECONDS);
        if (json == null) {
            throw new AssertionError("未收到出站请求");
        }
        return assertInstanceOf(JsonRpcMessage.Request.class, JsonRpcCodec.decode(json));
    }
}
