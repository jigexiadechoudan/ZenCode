package dev.zencode.hook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;

/**
 * http 执行器：发起 HTTP 请求，返回响应体文本。
 * <p>非 2xx 状态码与请求异常返回错误描述文本，不抛异常。</p>
 */
public final class HttpExecutor implements HookActionExecutor {

    private static final int MAX_BODY_CHARS = 4096;

    @Override
    public String execute(Action action, HookContext context) {
        if (!(action instanceof HttpAction http)) {
            throw new IllegalArgumentException("HttpExecutor 只接受 http 动作");
        }
        String url = VariableResolver.resolve(http.url(), context);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(http.timeout())
                    .build();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(http.timeout());
            http.headers().forEach(builder::header);
            builder.method(http.method().toUpperCase(Locale.ROOT), HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                return "HTTP 状态码: " + status;
            }
            String body = response.body();
            if (body.length() > MAX_BODY_CHARS) {
                body = body.substring(0, MAX_BODY_CHARS) + "...";
            }
            return body;
        } catch (Exception e) {
            return "HTTP 请求失败: " + e.getMessage();
        }
    }
}
