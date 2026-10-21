package dev.zencode.mcp;

import dev.zencode.mcp.client.McpClient;
import dev.zencode.mcp.jsonrpc.JsonRpcCodec;
import dev.zencode.mcp.jsonrpc.JsonRpcMessage;
import dev.zencode.mcp.testutil.FakeTransport;
import dev.zencode.permission.DangerCommandDetector;
import dev.zencode.permission.PathSandbox;
import dev.zencode.permission.PermissionChecker;
import dev.zencode.permission.PermissionDecision;
import dev.zencode.permission.PermissionMode;
import dev.zencode.permission.rule.RuleEngine;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.WorkspacePathResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适配层测试：验证 MCP 工具包装成内部 Tool 契约后的元信息与结果映射。
 */
class McpToolWrapperTest {

    @Test
    void metadata() {
        McpToolInfo info = new McpToolInfo("list_issues", "列出 issue",
                Map.of("type", "object", "properties", Map.of()));
        McpToolWrapper wrapper = new McpToolWrapper(
                new McpClient("github", new FakeTransport(), Duration.ofSeconds(5)), info, "github");

        assertEquals("github:list_issues", wrapper.name());
        assertEquals("列出 issue", wrapper.description());
        assertEquals(ToolCategory.COMMAND, wrapper.category());
        assertEquals(ToolPermission.REQUIRES_CONFIRMATION, wrapper.permission());
        assertEquals("object", wrapper.schema().get("type"));
    }

    @Test
    void successTextResult() throws Exception {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("github", transport, Duration.ofSeconds(5));
        McpToolWrapper wrapper = wrapper(client);

        ToolResult result = executeAndReply(wrapper, transport, Map.of("repo", "a/b"),
                null, Map.of("content", List.of(Map.of("type", "text", "text", "issue 1"))));

        assertTrue(result.success());
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertEquals("github:list_issues", result.toolName());
        assertEquals("issue 1", result.content());
    }

    @Test
    void errorResponseMapsToError() throws Exception {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("github", transport, Duration.ofSeconds(5));
        McpToolWrapper wrapper = wrapper(client);

        ToolResult result = executeAndReply(wrapper, transport, Map.of(),
                new JsonRpcMessage.JsonRpcError(-32000, "boom", null), null);

        assertFalse(result.success());
        assertEquals(ToolResult.Status.ERROR, result.status());
        assertTrue(result.content().contains("boom"));
    }

    @Test
    void isErrorResultMapsToError() throws Exception {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("github", transport, Duration.ofSeconds(5));
        McpToolWrapper wrapper = wrapper(client);

        ToolResult result = executeAndReply(wrapper, transport, Map.of(),
                null, Map.of("isError", true, "content",
                        List.of(Map.of("type", "text", "text", "权限不足"))));

        assertFalse(result.success());
        assertEquals(ToolResult.Status.ERROR, result.status());
        assertTrue(result.content().contains("权限不足"));
    }

    @Test
    void timeoutMapsToTimeout() {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("github", transport, Duration.ofMillis(300));
        McpToolWrapper wrapper = wrapper(client);

        ToolResult result = wrapper.execute(Map.of(),
                new ToolExecutionContext(Path.of("."), "call-1"));

        assertEquals(ToolResult.Status.TIMEOUT, result.status());
        assertFalse(result.success());
    }

    @Test
    void cancelledDoesNotSend() {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("github", transport, Duration.ofSeconds(5));
        McpToolWrapper wrapper = wrapper(client);

        ToolResult result = wrapper.execute(Map.of(),
                new ToolExecutionContext(Path.of("."), "call-2", new AtomicBoolean(true)));

        assertEquals(ToolResult.Status.CANCELLED, result.status());
        assertTrue(transport.sentAll.isEmpty(), "取消的调用不应向 Server 发送请求");
    }

    @Test
    void nonTextResultSerializedToJson() throws Exception {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("github", transport, Duration.ofSeconds(5));
        McpToolWrapper wrapper = wrapper(client);

        ToolResult result = executeAndReply(wrapper, transport, Map.of(),
                null, Map.of("some", "value"));

        assertTrue(result.success());
        assertTrue(result.content().contains("\"some\""));
    }

    @Test
    void lockedModeDeniesWithoutSending() {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("github", transport, Duration.ofSeconds(5));
        McpToolWrapper wrapper = wrapper(client);
        ToolRegistry registry = ToolRegistry.createDefault(Path.of("."));
        registry.register(wrapper);

        RuleEngine ruleEngine = new RuleEngine(Path.of("no-project-rules.yaml"), Path.of("no-user-rules.yaml"));
        PermissionChecker checker = new PermissionChecker(
                new DangerCommandDetector(),
                new PathSandbox(new WorkspacePathResolver(Path.of("."))),
                ruleEngine, PermissionMode.LOCKED, registry);

        ToolCall call = new ToolCall("c1", "github:list_issues", Map.of(), "", "anthropic");
        PermissionChecker.CheckResult result = checker.check(call);

        assertEquals(PermissionDecision.DENY_BLOCKED, result.decision());
        assertTrue(transport.sentAll.isEmpty(), "locked 模式下不应向 Server 发送调用");
    }

    @Test
    void defaultModeAsksForHitl() {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("github", transport, Duration.ofSeconds(5));
        McpToolWrapper wrapper = wrapper(client);
        ToolRegistry registry = ToolRegistry.createDefault(Path.of("."));
        registry.register(wrapper);

        RuleEngine ruleEngine = new RuleEngine(Path.of("no-project-rules.yaml"), Path.of("no-user-rules.yaml"));
        PermissionChecker checker = new PermissionChecker(
                new DangerCommandDetector(),
                new PathSandbox(new WorkspacePathResolver(Path.of("."))),
                ruleEngine, PermissionMode.DEFAULT, registry);

        ToolCall call = new ToolCall("c1", "github:list_issues", Map.of(), "", "anthropic");
        PermissionChecker.CheckResult result = checker.check(call);

        assertEquals(PermissionDecision.REQUEST_HITL, result.decision());
    }

    private static McpToolWrapper wrapper(McpClient client) {
        return new McpToolWrapper(client,
                new McpToolInfo("list_issues", "列出 issue", Map.of("type", "object")), "github");
    }

    private static ToolResult executeAndReply(McpToolWrapper wrapper, FakeTransport transport,
                                              Map<String, Object> args,
                                              JsonRpcMessage.JsonRpcError error, Object result) throws Exception {
        CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(() -> wrapper.execute(
                args, new ToolExecutionContext(Path.of("."), "call-1")));
        String requestJson = transport.requestQueue.poll(5, TimeUnit.SECONDS);
        if (requestJson == null) {
            throw new AssertionError("未收到 tools/call 请求");
        }
        JsonRpcMessage.Request req = (JsonRpcMessage.Request) JsonRpcCodec.decode(requestJson);
        assertEquals("tools/call", req.method());
        if (error != null) {
            transport.reply(JsonRpcCodec.encode(JsonRpcMessage.Response.error(req.id(), error)));
        } else {
            transport.reply(JsonRpcCodec.encode(JsonRpcMessage.Response.result(req.id(), result)));
        }
        return future.get(5, TimeUnit.SECONDS);
    }
}
