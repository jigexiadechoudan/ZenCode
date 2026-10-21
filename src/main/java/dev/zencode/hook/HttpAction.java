package dev.zencode.hook;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/** http 动作：发起 HTTP 请求，支持方法、请求头与超时。 */
public record HttpAction(String url, String method, Map<String, String> headers,
                         Duration timeout) implements Action {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public HttpAction {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("http 动作缺少 url");
        }
        method = (method == null || method.isBlank()) ? "GET" : method.toUpperCase(Locale.ROOT);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }
}
