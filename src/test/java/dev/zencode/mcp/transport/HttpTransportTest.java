package dev.zencode.mcp.transport;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Streamable HTTP 传输测试：用 JDK 内置 HttpServer 桩验证单次 POST 往返、
 * SSE data 响应体、请求头、错误状态码与超时。
 */
class HttpTransportTest {
    private HttpServer server;
    private URI base;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void postJsonRoundTrip() throws Exception {
        String payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}";
        server.createContext("/json", ex -> {
            respond(ex, 200, "application/json", payload);
        });
        HttpTransport transport = new HttpTransport(base.resolve("/json"), Map.of(), Duration.ofSeconds(5));
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        transport.setMessageHandler(received::add);
        transport.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
        assertEquals(payload, received.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void sseDataRoundTrip() throws Exception {
        String inner = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}";
        server.createContext("/sse", ex -> {
            respond(ex, 200, "text/event-stream", "data: " + inner + "\n\n");
        });
        HttpTransport transport = new HttpTransport(base.resolve("/sse"), Map.of(), Duration.ofSeconds(5));
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        transport.setMessageHandler(received::add);
        transport.send("{}");
        assertEquals(inner, received.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void customHeadersSent() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/headers", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, 200, "application/json", "{}");
        });
        HttpTransport transport = new HttpTransport(base.resolve("/headers"),
                Map.of("Authorization", "Bearer test-token"), Duration.ofSeconds(5));
        transport.setMessageHandler(msg -> { });
        transport.send("{}");
        assertEquals("Bearer test-token", auth.get());
    }

    @Test
    void non2xxThrows() {
        server.createContext("/error", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        HttpTransport transport = new HttpTransport(base.resolve("/error"), Map.of(), Duration.ofSeconds(5));
        transport.setMessageHandler(msg -> { });
        assertThrows(TransportException.class, () -> transport.send("{}"));
    }

    @Test
    void timeoutThrows() {
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                // 忽略
            }
            respond(ex, 200, "application/json", "{}");
        });
        HttpTransport transport = new HttpTransport(base.resolve("/slow"), Map.of(), Duration.ofMillis(500));
        transport.setMessageHandler(msg -> { });
        long start = System.nanoTime();
        assertThrows(TransportException.class, () -> transport.send("{}"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        if (elapsedMillis > 2500) {
            throw new AssertionError("调用应受超时约束，实际耗时(ms)=" + elapsedMillis);
        }
    }

    @Test
    void extractPayloadUnit() {
        assertEquals("{\"a\":1}", HttpTransport.extractPayload("{\"a\":1}"));
        assertEquals("{\"a\":1}", HttpTransport.extractPayload("data: {\"a\":1}\n\n"));
        assertEquals("{\"a\":1}", HttpTransport.extractPayload("event: message\ndata: {\"a\":1}\n\n"));
        assertThrows(TransportException.class, () -> HttpTransport.extractPayload(""));
        assertThrows(TransportException.class, () -> HttpTransport.extractPayload("data: \n\n"));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code,
                                String contentType, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(code, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        } catch (IOException ignored) {
            // 桩异常忽略
        }
    }
}
