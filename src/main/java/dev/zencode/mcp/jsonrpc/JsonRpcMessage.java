package dev.zencode.mcp.jsonrpc;

import java.util.Map;

/**
 * JSON-RPC 2.0 消息模型：请求、响应、通知三种消息。
 * <p>
 * sealed 接口保证消息种类在编译期封闭。消息均携带 {@code jsonrpc: "2.0"} 字段，
 * 由 {@link JsonRpcCodec} 在编解码时处理。
 * </p>
 */
public sealed interface JsonRpcMessage permits JsonRpcMessage.Request, JsonRpcMessage.Response, JsonRpcMessage.Notification {

    /** JSON-RPC 请求：含 id、method、可选 params。 */
    record Request(Object id, String method, Map<String, Object> params) implements JsonRpcMessage {
        public Request {
            if (id == null) throw new IllegalArgumentException("请求 id 不能为空");
            if (method == null || method.isBlank()) throw new IllegalArgumentException("请求 method 不能为空");
        }
    }

    /** JSON-RPC 响应：按 id 关联请求，result 与 error 二选一（error 优先）。 */
    record Response(Object id, Object result, JsonRpcError error) implements JsonRpcMessage {
        public Response {
            if (id == null) throw new IllegalArgumentException("响应 id 不能为空");
        }

        /** 创建成功响应。 */
        public static Response result(Object id, Object result) {
            return new Response(id, result, null);
        }

        /** 创建错误响应。 */
        public static Response error(Object id, JsonRpcError error) {
            return new Response(id, null, error);
        }
    }

    /** JSON-RPC 通知：无 id，不期待响应。 */
    record Notification(String method, Map<String, Object> params) implements JsonRpcMessage {
        public Notification {
            if (method == null || method.isBlank()) throw new IllegalArgumentException("通知 method 不能为空");
        }
    }

    /** JSON-RPC 错误对象：code + message，可选 data。 */
    record JsonRpcError(int code, String message, Object data) {
        public JsonRpcError {
            if (message == null) throw new IllegalArgumentException("错误消息不能为空");
        }

        public JsonRpcError(int code, String message) {
            this(code, message, null);
        }
    }
}
