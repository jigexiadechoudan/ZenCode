package dev.zencode.mcp;

import dev.zencode.config.McpServerConfig;
import dev.zencode.mcp.client.McpClient;
import dev.zencode.mcp.transport.Transport;
import dev.zencode.tool.ToolRegistry;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * MCP 管理器：连接缓存、配置装配与生命周期管理。
 * <p>
 * 对每个配置的 Server 建立客户端、握手、发现工具并注册进 {@link ToolRegistry}；
 * 同一 Server 的客户端在会话内缓存复用，退出时统一关闭。
 * 任一 Server 接入失败只记日志并跳过，不影响其余 Server 与内置工具。
 * </p>
 */
public final class McpManager implements AutoCloseable {

    /** 传输工厂：按配置创建传输实现，便于测试注入。 */
    @FunctionalInterface
    public interface TransportFactory {
        Transport create(McpServerConfig config) throws IOException;
    }

    private static final String CLIENT_NAME = "zencode";
    private static final String CLIENT_VERSION = "0.1.0";

    private final ToolRegistry registry;
    private final Map<String, McpClient> clients = new LinkedHashMap<>();
    private final TransportFactory transportFactory;

    public McpManager(ToolRegistry registry) {
        this(registry, McpManager::defaultTransport);
    }

    public McpManager(ToolRegistry registry, TransportFactory transportFactory) {
        this.registry = Objects.requireNonNull(registry, "工具注册中心不能为空");
        this.transportFactory = Objects.requireNonNull(transportFactory, "传输工厂不能为空");
    }

    /**
     * 连接并接入所有配置的 Server，注册其工具。
     *
     * @return 成功接入的 Server 数；失败的 Server 记日志并跳过
     */
    public int connectAll(List<McpServerConfig> servers) {
        int connected = 0;
        for (McpServerConfig config : servers) {
            try {
                connectOne(config);
                connected++;
            } catch (Exception e) {
                System.err.println("[mcp-manager] 接入 server 失败，已跳过: " + config.name()
                        + " - " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return connected;
    }

    private void connectOne(McpServerConfig config) throws Exception {
        Transport transport = transportFactory.create(config);
        McpClient client = new McpClient(config.name(), transport,
                Duration.ofMillis(config.timeoutMillis()));
        try {
            client.initialize(Map.of("name", CLIENT_NAME, "version", CLIENT_VERSION),
                    Map.of("tools", Map.of()));
            List<McpToolInfo> tools = client.listTools();
            for (McpToolInfo tool : tools) {
                registry.register(new McpToolWrapper(client, tool, config.name()));
            }
            clients.put(config.name(), client);
        } catch (Exception e) {
            client.close();
            throw e;
        }
    }

    /** 默认传输工厂：stdio 建子进程传输，http 建单次 POST 传输。 */
    public static Transport defaultTransport(McpServerConfig config) throws IOException {
        if (config.isStdio()) {
            return new dev.zencode.mcp.transport.StdioTransport(
                    config.command(), config.args(), config.env());
        }
        return new dev.zencode.mcp.transport.HttpTransport(
                config.url(), config.headers(), Duration.ofMillis(config.timeoutMillis()));
    }

    /** 已缓存的客户端（按 Server 名）。只读视图。 */
    public Map<String, McpClient> clients() {
        return Collections.unmodifiableMap(clients);
    }

    /** 会话结束统一关闭所有客户端与子进程。 */
    @Override
    public void close() {
        for (McpClient client : clients.values()) {
            try {
                client.close();
            } catch (RuntimeException e) {
                System.err.println("[mcp-manager] 关闭 client 异常: " + e.getMessage());
            }
        }
        clients.clear();
    }
}
