package dev.zencode.provider;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class ProviderFailureMessagesTest {
    private static final String PRIVATE_DETAIL = "private-test-key-and-endpoint";

    @Test
    void explainsHttpFailuresWithoutClaimingEveryFailureIsAnInvalidKey() {
        Map<Integer, String> expected = Map.of(
                401, "认证未通过", 403, "权限", 404, "Base URL", 429, "额度",
                500, "网关异常", 503, "网关异常", 504, "超时", 400, "参数",
                302, "重定向");
        expected.forEach((status, hint) -> {
            String message = ProviderFailureMessages.httpStatus(status);
            assertTrue(message.contains("HTTP " + status));
            assertTrue(message.contains(hint));
        });
        assertTrue(ProviderFailureMessages.httpStatus(401).contains("不代表密钥一定填写错误"));
    }

    @Test
    void findsDnsFailureInsideAsyncAndConnectionWrappers() {
        ConnectException connection = new ConnectException(PRIVATE_DETAIL);
        connection.initCause(new UnknownHostException(PRIVATE_DETAIL));
        Throwable failure = new CompletionException(new ExecutionException(connection));
        assertCategory(failure, "DNS");
        assertCategory(new UnresolvedAddressException(), "DNS");
    }

    @Test
    void distinguishesTlsTimeoutConnectionAndIo() {
        assertCategory(new IOException(PRIVATE_DETAIL, new SSLHandshakeException(PRIVATE_DETAIL)), "TLS");
        assertCategory(new HttpConnectTimeoutException(PRIVATE_DETAIL), "TIMEOUT");
        assertCategory(new SocketTimeoutException(PRIVATE_DETAIL), "TIMEOUT");
        assertCategory(new ConnectException(PRIVATE_DETAIL), "CONNECT");
        assertCategory(new IOException(PRIVATE_DETAIL), "IO");
    }

    @Test
    void differentiatesStreamFailureWithoutEchoingExceptionDetails() {
        String message = ProviderFailureMessages.transport(new IOException(PRIVATE_DETAIL), true);
        assertTrue(message.contains("响应读取失败（IO）"));
        assertFalse(message.contains(PRIVATE_DETAIL));
    }

    @Test
    void handlesUnknownFailureAndMissingCauseSafely() {
        assertCategory(new IllegalStateException(PRIVATE_DETAIL), "REQUEST");
        assertCategory(null, "REQUEST");
    }

    @Test
    void doesNotReadExceptionMessagesAndTerminatesOnCauseCycles() {
        IOException first = new IOException() {
            @Override public String getMessage() { throw new AssertionError("Must not read raw failure details"); }
        };
        RuntimeException second = new RuntimeException(PRIVATE_DETAIL);
        first.initCause(second);
        second.initCause(first);
        assertCategory(first, "IO");
    }

    private static void assertCategory(Throwable error, String category) {
        String message = ProviderFailureMessages.transport(error, false);
        assertTrue(message.contains("（" + category + "）"), message);
        assertFalse(message.contains(PRIVATE_DETAIL));
    }
}
