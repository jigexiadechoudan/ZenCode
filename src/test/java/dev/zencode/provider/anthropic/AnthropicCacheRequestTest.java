package dev.zencode.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zencode.chat.ChatMessage;
import dev.zencode.chat.Role;
import dev.zencode.config.Protocol;
import dev.zencode.config.ProviderConfig;
import dev.zencode.provider.ProviderRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** T12/N7：Anthropic 请求的 prompt cache 控制——system 与 tools 断点。 */
class AnthropicCacheRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private com.sun.net.httpserver.HttpServer server;
    private URI baseUrl;
    private volatile String latestBody = "";

    @BeforeEach
    void startServer() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            latestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = "event: message_start\ndata: {\"type\":\"message_start\",\"usage\":{}}\n\n"
                    + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AnthropicProvider newProvider() {
        return new AnthropicProvider(
                new ProviderConfig("anthropic", Protocol.ANTHROPIC, "claude-test", baseUrl, "test-key", false),
                HttpClient.newHttpClient());
    }

    private void runOnce(ProviderRequest request) throws Exception {
        newProvider().stream(request, e -> {}).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
    }

    @Test
    void systemArrayHasEphemeralCacheControl() throws Exception {
        Map<String, Object> tool = Map.of("name", "read_file", "description", "读取",
                "input_schema", Map.of("type", "object"));
        runOnce(new ProviderRequest(List.of(), new ChatMessage(Role.USER, "读取"), "顶级指令",
                List.of(tool)));

        JsonNode json = mapper.readTree(latestBody);
        assertTrue(json.path("system").isArray());
        JsonNode block = json.path("system").get(0);
        assertEquals("text", block.path("type").asText());
        assertEquals("ephemeral", block.path("cache_control").path("type").asText());
    }

    @Test
    void lastToolGetsEphemeralCacheControl() throws Exception {
        Map<String, Object> toolA = Map.of("name", "read_file", "description", "a",
                "input_schema", Map.of("type", "object"));
        Map<String, Object> toolB = Map.of("name", "write_file", "description", "b",
                "input_schema", Map.of("type", "object"));
        runOnce(new ProviderRequest(List.of(), new ChatMessage(Role.USER, "任务"), null,
                List.of(toolA, toolB)));

        JsonNode json = mapper.readTree(latestBody);
        JsonNode tools = json.path("tools");
        assertTrue(tools.isArray());
        assertFalse(tools.get(0).path("cache_control").isObject(), "非最后工具不应有缓存断点");
        assertEquals("ephemeral", tools.get(1).path("cache_control").path("type").asText(),
                "仅最后一个工具应设置缓存断点");
    }
}