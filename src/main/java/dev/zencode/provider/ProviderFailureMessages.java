package dev.zencode.provider;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** Safe, actionable diagnostics. Never include exception messages, URLs or response bodies. */
public final class ProviderFailureMessages {
    private ProviderFailureMessages() { }

    public static String httpStatus(int status) {
        String advice = switch (status) {
            case 400, 422 -> "请求参数不被接受，请检查模型名称、协议及服务商支持的参数。";
            case 401 -> "认证未通过，请检查当前已应用配置的 API Key 及其所属服务；不代表密钥一定填写错误。";
            case 403 -> "服务拒绝访问，请检查账号、模型权限及服务访问限制。";
            case 404 -> "请求路径或资源不存在，请检查 Base URL、协议及模型名称。";
            case 408, 504 -> "服务或网关等待超时，可稍后重试并检查服务状态。";
            case 413 -> "请求内容过大，请减少上下文或工具结果后重试。";
            case 429 -> "请求受限，请检查调用频率、并发和账号额度后重试。";
            default -> {
                if (status >= 500 && status <= 599) {
                    yield "模型服务或网关异常，可稍后重试并检查服务状态。";
                }
                if (status >= 300 && status <= 399) {
                    yield "服务返回重定向，请直接配置服务商提供的 API 地址。";
                }
                yield "模型服务未接受请求，请检查当前配置及服务状态。";
            }
        };
        return "模型请求失败（HTTP " + status + "）：" + advice;
    }

    public static String transport(Throwable error, boolean readingStream) {
        // Inspect types only. Bound traversal and detect cycles in nested asynchronous failures.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean dns = false, tls = false, timeout = false, connection = false, io = false;
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 32 && seen.add(cause); depth++) {
            dns |= cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException;
            tls |= cause instanceof SSLException;
            timeout |= cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException;
            connection |= cause instanceof ConnectException || cause instanceof NoRouteToHostException;
            io |= cause instanceof IOException;
            cause = cause.getCause();
        }
        if (dns) return "模型连接失败（DNS）：无法解析服务地址，请检查 Base URL 域名、DNS 或代理配置。";
        if (tls) return "模型连接失败（TLS）：安全连接或证书校验失败，请检查服务证书、系统时间及代理；不要关闭证书校验。";
        if (timeout) return "模型请求超时（TIMEOUT）：请检查网络、代理及服务响应情况后重试。";
        if (connection) return "模型连接失败（CONNECT）：连接失败、被拒绝或不可达，请检查 Base URL 端口、服务状态及代理配置。";
        if (io) return readingStream
                ? "模型响应读取失败（IO）：连接中断或发生传输异常，请检查网络、代理及服务状态；这不等于密钥无效。"
                : "模型请求失败（IO）：连接发生传输异常，请检查网络、代理及服务状态；这不等于密钥无效。";
        return readingStream
                ? "模型响应处理失败（STREAM）：请检查服务是否支持当前协议的流式响应。"
                : "模型请求失败（REQUEST）：请求未能正常完成，请检查当前配置及服务状态。";
    }
}
