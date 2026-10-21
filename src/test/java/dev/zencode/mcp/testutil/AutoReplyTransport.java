package dev.zencode.mcp.testutil;

import dev.zencode.mcp.jsonrpc.JsonRpcCodec;
import dev.zencode.mcp.jsonrpc.JsonRpcMessage;
import dev.zencode.mcp.transport.Transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 自动应答假传输：对 initialize / tools/list / tools/call 请求自动按 id 回包，
 * 供 McpManager 端到端连接测试使用。
 */
public final class AutoReplyTransport implements Transport {
    public final List<String> sent = new ArrayList<>();
    public final List<Map<String, Object>> tools;
    public final int[] initializeCount = {0};
    public volatile boolean closed;
    public volatile Consumer<String> handler;

    public AutoReplyTransport(List<Map<String, Object>> tools) {
        this.tools = tools;
    }

    @Override
    public void send(String message) {
        sent.add(message);
        JsonRpcMessage msg = JsonRpcCodec.decode(message);
        if (msg instanceof JsonRpcMessage.Request req) {
            switch (req.method()) {
                case "initialize" -> {
                    initializeCount[0]++;
                    reply(JsonRpcCodec.encode(JsonRpcMessage.Response.result(req.id(),
                            Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(),
                                    "serverInfo", Map.of("name", "fake")))));
                }
                case "tools/list" -> reply(JsonRpcCodec.encode(
                        JsonRpcMessage.Response.result(req.id(), Map.of("tools", tools))));
                case "tools/call" -> reply(JsonRpcCodec.encode(JsonRpcMessage.Response.result(req.id(),
                        Map.of("content", List.of(Map.of("type", "text", "text", "ok"))))));
                default -> { }
            }
        }
    }

    private void reply(String json) {
        handler.accept(json);
    }

    @Override
    public void setMessageHandler(Consumer<String> handler) {
        this.handler = handler;
    }

    @Override
    public boolean isAlive() {
        return !closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
