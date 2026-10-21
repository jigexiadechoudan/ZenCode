package dev.zencode.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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

/** T13：OpenAI 请求串行化——依赖服务端自动缓存，不发 cache_control。 */
class OpenAiCachePayloadTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private URI baseUrl;
    private volatile String latestBody = "";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            latestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = "".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
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

    private OpenAiProvider newProvider() {
        return new OpenAiProvider(
                new ProviderConfig("openai", Protocol.OPENAI, "gpt-test", baseUrl, "test-key", false),
                HttpClient.newHttpClient());
    }

    private void runOnce(ProviderRequest request) throws Exception {
        newProvider().stream(request, e -> {}).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
    }

    @Test
    void systemIsPlainFirstMessageWithoutCacheControl() throws Exception {
        runOnce(new ProviderRequest(List.of(), new ChatMessage(Role.USER, "读取"), "顶级指令", List.of()));
        JsonNode json = mapper.readTree(latestBody);
        JsonNode messages = json.path("messages");
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("顶级指令", messages.get(0).path("content").asText());
        assertFalse(messages.get(0).has("cache_control"), "OpenAI 不应发送 cache_control");
    }

    @Test
    void toolsSentWithoutCacheBreakpointAndIncludeUsageRequested() throws Exception {
        Map<String, Object> tool = Map.of("type", "function",
                "function", Map.of("name", "read_file", "parameters", Map.of("type", "object")));
        runOnce(new ProviderRequest(List.of(), new ChatMessage(Role.USER, "任务"), null, List.of(tool)));
        JsonNode json = mapper.readTree(latestBody);
        assertTrue(json.path("tools").isArray());
        assertFalse(json.path("tools").has("cache_control"), "tools 数组不应携带 cache_control");
        assertEquals(true, json.path("stream_options").path("include_usage").asBoolean(),
                "应请求包含 usage 以支持跨协议缓存统计");
    }
}