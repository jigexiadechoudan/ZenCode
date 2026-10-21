package dev.zencode.mcp;

import dev.zencode.config.McpServerConfig;
import dev.zencode.mcp.testutil.AutoReplyTransport;
import dev.zencode.tool.Tool;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 管理器测试：验证工具注册、失败隔离、连接缓存复用与生命周期关闭。
 */
class McpManagerTest {
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = ToolRegistry.createDefault(Path.of("."));
    }

    @Test
    void connectAllRegistersTools() {
        McpManager manager = managerWith(config -> config.name().equals("github")
                ? List.of(Map.of("name", "list_issues", "description", "列出 issue",
                        "inputSchema", Map.of("type", "object")))
                : List.of(Map.of("name", "post_message", "inputSchema", Map.of("type", "object"))));
        int connected = manager.connectAll(List.of(
                stdio("github", List.of()),
                stdio("slack", List.of())));

        assertEquals(2, connected);
        assertTrue(registry.find("github:list_issues").isPresent(), "github:list_issues 应已注册");
        assertTrue(registry.find("slack:post_message").isPresent(), "slack:post_message 应已注册");
        assertEquals("列出 issue", registry.find("github:list_issues").get().description());
        // 内置工具不受影响
        assertTrue(registry.find("ReadFile").isPresent());
        assertTrue(registry.find("Bash").isPresent());
    }

    @Test
    void failureIsolation() {
        McpManager manager = new McpManager(registry, config -> {
            if (config.name().equals("bad")) {
                throw new IOException("启动失败");
            }
            return new AutoReplyTransport(List.of(Map.of("name", "ok_tool", "inputSchema", Map.of("type", "object"))));
        });
        int connected = manager.connectAll(List.of(
                stdio("bad", List.of()),
                stdio("good", List.of(Map.of("name", "ok_tool", "inputSchema", Map.of("type", "object"))))));

        assertEquals(1, connected);
        assertTrue(registry.find("good:ok_tool").isPresent(), "正常 server 的工具应注册");
        assertFalse(manager.clients().containsKey("bad"), "失败 server 不应缓存客户端");
        assertTrue(manager.clients().containsKey("good"));
    }

    @Test
    void clientReusedAcrossCalls() throws Exception {
        List<AutoReplyTransport> created = new ArrayList<>();
        McpManager manager = new McpManager(registry, config -> {
            AutoReplyTransport transport = new AutoReplyTransport(
                    List.of(Map.of("name", "list_issues", "inputSchema", Map.of("type", "object"))));
            created.add(transport);
            return transport;
        });
        manager.connectAll(List.of(stdio("github", List.of(Map.of("name", "list_issues")))));

        assertEquals(1, created.size(), "会话内应只创建一个传输/客户端");
        assertEquals(1, created.get(0).initializeCount[0], "握手应只发生一次");

        Tool tool = registry.find("github:list_issues").orElseThrow();
        ToolResult r1 = tool.execute(Map.of(), new ToolExecutionContext(Path.of("."), "c1"));
        ToolResult r2 = tool.execute(Map.of(), new ToolExecutionContext(Path.of("."), "c2"));
        assertTrue(r1.success());
        assertTrue(r2.success());
        assertEquals(1, created.get(0).initializeCount[0], "多次调用不应重新握手");
    }

    @Test
    void shutdownClosesAllClients() {
        List<AutoReplyTransport> created = new ArrayList<>();
        McpManager manager = new McpManager(registry, config -> {
            AutoReplyTransport transport = new AutoReplyTransport(List.of());
            created.add(transport);
            return transport;
        });
        manager.connectAll(List.of(stdio("a", List.of()), stdio("b", List.of())));
        assertEquals(2, created.size());

        manager.close();

        assertTrue(created.stream().allMatch(t -> t.closed), "所有客户端应被关闭");
        assertTrue(manager.clients().isEmpty());
    }

    @Test
    void emptyToolListConnectsWithoutError() {
        McpManager manager = managerWith(config -> List.of());
        int connected = manager.connectAll(List.of(stdio("empty", List.of())));

        assertEquals(1, connected);
        assertTrue(manager.clients().containsKey("empty"));
        assertFalse(registry.find("empty:anything").isPresent(), "空工具列表不应注册任何工具");
    }

    @Test
    void sameToolNameAcrossServersNoConflict() {
        McpManager manager = managerWith(config ->
                List.of(Map.of("name", "query", "inputSchema", Map.of("type", "object"))));
        int connected = manager.connectAll(List.of(stdio("db1", List.of()), stdio("db2", List.of())));

        assertEquals(2, connected);
        assertTrue(registry.find("db1:query").isPresent(), "db1:query 应注册");
        assertTrue(registry.find("db2:query").isPresent(), "db2:query 应注册");
        assertFalse(registry.find("query").isPresent(), "不应注册未加前缀的裸工具名");
    }

    /** 按 server 名提供工具列表的 manager 工厂。 */
    private McpManager managerWith(java.util.function.Function<McpServerConfig, List<Map<String, Object>>> toolsFor) {
        return new McpManager(registry, config -> new AutoReplyTransport(toolsFor.apply(config)));
    }

    private static McpServerConfig stdio(String name, List<Map<String, Object>> tools) {
        return new McpServerConfig(name, "stdio", "cmd.exe", List.of(),
                null, Map.of(), Map.of(), 5000);
    }
}
