package dev.zencode.mcp.jsonrpc;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static dev.zencode.mcp.jsonrpc.JsonRpcErrorCode.INVALID_REQUEST;
import static dev.zencode.mcp.jsonrpc.JsonRpcErrorCode.PARSE_ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonRpcCodecTest {

    @Test
    void requestRoundTrip() {
        JsonRpcMessage.Request req = new JsonRpcMessage.Request(1L, "tools/list", Map.of());
        String json = JsonRpcCodec.encode(req);
        JsonRpcMessage decoded = JsonRpcCodec.decode(json);
        JsonRpcMessage.Request out = assertInstanceOf(JsonRpcMessage.Request.class, decoded);
        assertEquals(1L, out.id());
        assertEquals("tools/list", out.method());
        assertEquals(Map.of(), out.params());
    }

    @Test
    void requestWithParamsRoundTrip() {
        JsonRpcMessage.Request req = new JsonRpcMessage.Request(42L, "tools/call",
                Map.of("name", "list_issues", "arguments", Map.of("repo", "a/b")));
        String json = JsonRpcCodec.encode(req);
        JsonRpcMessage.Request out = assertInstanceOf(JsonRpcMessage.Request.class, JsonRpcCodec.decode(json));
        assertEquals(42L, out.id());
        assertEquals("list_issues", out.params().get("name"));
    }

    @Test
    void responseResultRoundTrip() {
        String json = JsonRpcCodec.encode(JsonRpcMessage.Response.result(7L, Map.of("ok", true, "count", 3)));
        JsonRpcMessage.Response out = assertInstanceOf(JsonRpcMessage.Response.class, JsonRpcCodec.decode(json));
        assertEquals(7L, out.id());
        assertNull(out.error());
        assertEquals(3, ((Map<?, ?>) out.result()).get("count"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) out.result()).get("ok"));
    }

    @Test
    void responseErrorRoundTrip() {
        JsonRpcMessage.JsonRpcError err = new JsonRpcMessage.JsonRpcError(-32601, "方法不存在", Map.of("detail", "x"));
        String json = JsonRpcCodec.encode(JsonRpcMessage.Response.error(3L, err));
        JsonRpcMessage.Response out = assertInstanceOf(JsonRpcMessage.Response.class, JsonRpcCodec.decode(json));
        assertEquals(3L, out.id());
        assertNull(out.result());
        assertEquals(-32601, out.error().code());
        assertEquals("方法不存在", out.error().message());
        assertEquals(Map.of("detail", "x"), out.error().data());
    }

    @Test
    void notificationRoundTrip() {
        JsonRpcMessage.Notification not = new JsonRpcMessage.Notification("notifications/initialized",
                Map.of("k", "v"));
        String json = JsonRpcCodec.encode(not);
        JsonRpcMessage.Notification out =
                assertInstanceOf(JsonRpcMessage.Notification.class, JsonRpcCodec.decode(json));
        assertEquals("notifications/initialized", out.method());
        assertEquals(Map.of("k", "v"), out.params());
        assertEquals(-1, json.indexOf("\"id\""));
    }

    @Test
    void stringIdRoundTrip() {
        JsonRpcMessage.Request req = new JsonRpcMessage.Request("abc", "ping", Map.of());
        JsonRpcMessage.Request out =
                assertInstanceOf(JsonRpcMessage.Request.class, JsonRpcCodec.decode(JsonRpcCodec.encode(req)));
        assertEquals("abc", out.id());
    }

    @Test
    void decodeInvalidJsonThrowsParseError() {
        JsonRpcException ex = assertThrows(JsonRpcException.class, () -> JsonRpcCodec.decode("{not json"));
        assertEquals(PARSE_ERROR.code(), ex.code());
    }

    @Test
    void decodeMissingJsonrpcThrowsInvalidRequest() {
        JsonRpcException ex = assertThrows(JsonRpcException.class,
                () -> JsonRpcCodec.decode("{\"id\":1,\"method\":\"ping\"}"));
        assertEquals(INVALID_REQUEST.code(), ex.code());
    }

    @Test
    void decodeResponseMissingResultAndErrorThrows() {
        JsonRpcException ex = assertThrows(JsonRpcException.class,
                () -> JsonRpcCodec.decode("{\"jsonrpc\":\"2.0\",\"id\":1}"));
        assertEquals(INVALID_REQUEST.code(), ex.code());
    }

    @Test
    void decodeNoIdNoMethodThrows() {
        JsonRpcException ex = assertThrows(JsonRpcException.class,
                () -> JsonRpcCodec.decode("{\"jsonrpc\":\"2.0\",\"params\":{}}"));
        assertEquals(INVALID_REQUEST.code(), ex.code());
    }

    @Test
    void decodeEmptyThrowsParseError() {
        JsonRpcException ex = assertThrows(JsonRpcException.class, () -> JsonRpcCodec.decode("  "));
        assertEquals(PARSE_ERROR.code(), ex.code());
    }
}
