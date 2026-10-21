package dev.zencode.mcp.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Streamable HTTP 传输：每次调用向配置 URL 发起一次 HTTP POST，从同一次响应体读回 JSON-RPC 消息。
 * <p>
 * 不建立 GET 长连接、不做 server 主动推送流（spec 边界）。兼容两种响应体：
 * 纯 JSON，或单条 SSE event（取 {@code data:} 字段）。响应体为空或非法时抛
 * {@link TransportException}。入站回调在 {@link #send} 内同步触发。
 * </p>
 */
public final class HttpTransport implements Transport {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final URI url;
    private final Map<String, String> headers;
    private final Duration timeout;
    private volatile Consumer<String> handler;

    public HttpTransport(URI url, Map<String, String> headers, Duration timeout) {
        this.url = Objects.requireNonNull(url, "URL 不能为空");
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.timeout = Objects.requireNonNull(timeout, "超时不能为空");
    }

    @Override
    public void send(String message) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message));
        headers.forEach(builder::header);
        final HttpResponse<String> response;
        try {
            response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new TransportException("HTTP 请求失败: " + e.getMessage(), e);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String body = response.body() == null ? "" : response.body();
            throw new TransportException("HTTP 状态码 " + status
                    + (body.isBlank() ? "" : ": " + truncate(body, 200)));
        }
        String payload = extractPayload(response.body());
        Consumer<String> h = handler;
        if (h != null) {
            h.accept(payload);
        }
    }

    /**
     * 从响应体提取 JSON-RPC 消息：
     * 若存在 {@code data:} 行视为 SSE event（取首个非空 data 字段）；
     * 否则整段视为纯 JSON 原样返回。
     */
    static String extractPayload(String body) {
        if (body == null || body.isBlank()) {
            throw new TransportException("HTTP 响应体为空");
        }
        String trimmed = body.trim();
        for (String line : trimmed.split("\\r?\\n")) {
            if (line.startsWith("data:")) {
                String value = line.substring("data:".length()).trim();
                if (!value.isEmpty()) {
                    return value;
                }
                throw new TransportException("SSE 响应缺少 data 字段");
            }
        }
        if (trimmed.startsWith("event:") || trimmed.startsWith("id:") || trimmed.startsWith("retry:")) {
            throw new TransportException("SSE 响应缺少 data 字段");
        }
        return trimmed;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    @Override
    public void setMessageHandler(Consumer<String> handler) {
        this.handler = Objects.requireNonNull(handler, "消息处理器不能为空");
    }

    @Override
    public boolean isAlive() {
        return true;
    }

    @Override
    public void close() {
        // 无长连接，无需释放
    }
}
