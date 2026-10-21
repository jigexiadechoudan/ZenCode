package dev.zencode.config;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 一个 MCP Server 的配置声明。
 * <p>
 * stdio 用 {@code command}/{@code args}；http 用 {@code url}/{@code headers}。
 * {@code env}/{@code headers} 中的值已在配置加载阶段把 {@code ${ENV}} 展开为宿主环境变量实际值。
 * </p>
 */
public record McpServerConfig(String name, String transport, String command, List<String> args,
                              URI url, Map<String, String> env, Map<String, String> headers,
                              long timeoutMillis) {

    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000;

    public McpServerConfig {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("MCP server 名称不能为空");
        if (transport == null || transport.isBlank()) throw new IllegalArgumentException("MCP server 传输方式不能为空");
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (timeoutMillis <= 0) timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
    }

    /** 默认调用超时（毫秒）。 */
    public static long defaultTimeoutMillis() {
        return DEFAULT_TIMEOUT_MILLIS;
    }

    /** 是否 stdio 传输。 */
    public boolean isStdio() {
        return "stdio".equalsIgnoreCase(transport);
    }

    /** 是否 http 传输。 */
    public boolean isHttp() {
        return "http".equalsIgnoreCase(transport);
    }
}
