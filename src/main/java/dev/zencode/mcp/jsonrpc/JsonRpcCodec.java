package dev.zencode.mcp.jsonrpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static dev.zencode.mcp.jsonrpc.JsonRpcErrorCode.INVALID_REQUEST;
import static dev.zencode.mcp.jsonrpc.JsonRpcErrorCode.INTERNAL_ERROR;
import static dev.zencode.mcp.jsonrpc.JsonRpcErrorCode.PARSE_ERROR;

/**
 * JSON-RPC 2.0 消息编解码器。
 * <p>
 * 编码：把 {@link JsonRpcMessage} 序列化为带 {@code jsonrpc: "2.0"} 字段的 JSON 字符串。
 * 解码：把 JSON 字符串还原为对应的消息类型；遇到无效 JSON 或无效请求对象时抛出
 * {@link JsonRpcException}（携带标准错误码），由上层决定如何构造错误响应。
 * 数字 id 统一规范化为 {@link Long}，保证请求与响应按 id 匹配时 equals 一致。
 * </p>
 */
public final class JsonRpcCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonRpcCodec() {}

    /** 序列化为 JSON 字符串。 */
    public static String encode(JsonRpcMessage message) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("jsonrpc", "2.0");
        switch (message) {
            case JsonRpcMessage.Request req -> {
                obj.put("id", req.id());
                obj.put("method", req.method());
                if (req.params() != null && !req.params().isEmpty()) {
                    obj.put("params", req.params());
                }
            }
            case JsonRpcMessage.Response resp -> {
                obj.put("id", resp.id());
                if (resp.error() != null) {
                    JsonRpcMessage.JsonRpcError err = resp.error();
                    Map<String, Object> errObj = new LinkedHashMap<>();
                    errObj.put("code", err.code());
                    errObj.put("message", err.message());
                    if (err.data() != null) {
                        errObj.put("data", err.data());
                    }
                    obj.put("error", errObj);
                } else {
                    obj.put("result", resp.result());
                }
            }
            case JsonRpcMessage.Notification not -> {
                obj.put("method", not.method());
                if (not.params() != null && !not.params().isEmpty()) {
                    obj.put("params", not.params());
                }
            }
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new JsonRpcException(INTERNAL_ERROR, "消息编码失败: " + e.getMessage());
        }
    }

    /**
     * 解码为 JSON-RPC 消息。
     *
     * @throws JsonRpcException 无效 JSON 抛 PARSE_ERROR；非法请求对象抛 INVALID_REQUEST
     */
    public static JsonRpcMessage decode(String text) {
        if (text == null || text.isBlank()) {
            throw new JsonRpcException(PARSE_ERROR, "空消息无法解析");
        }
        final JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new JsonRpcException(PARSE_ERROR, "无效 JSON: " + e.getMessage());
        }
        if (node == null || !node.isObject()) {
            throw new JsonRpcException(PARSE_ERROR, "消息必须是 JSON 对象");
        }
        JsonNode jsonrpc = node.get("jsonrpc");
        if (jsonrpc == null || !"2.0".equals(jsonrpc.asText())) {
            throw new JsonRpcException(INVALID_REQUEST, "缺少 jsonrpc: \"2.0\" 字段");
        }

        boolean hasId = node.has("id") && !node.isNull();
        String method = node.hasNonNull("method") ? node.get("method").asText() : null;

        if (hasId && method != null) {
            return new JsonRpcMessage.Request(normalizeId(node.get("id")), method, paramsOf(node));
        }
        if (hasId) {
            JsonNode errorNode = node.get("error");
            if (errorNode != null && errorNode.isObject()) {
                int code = errorNode.path("code").asInt();
                String message = errorNode.path("message").asText("未知错误");
                Object data = toJavaValue(errorNode.get("data"));
                return JsonRpcMessage.Response.error(normalizeId(node.get("id")),
                        new JsonRpcMessage.JsonRpcError(code, message, data));
            }
            if (node.has("result")) {
                return JsonRpcMessage.Response.result(normalizeId(node.get("id")), toJavaValue(node.get("result")));
            }
            throw new JsonRpcException(INVALID_REQUEST, "响应缺少 result 或 error");
        }
        if (method != null) {
            return new JsonRpcMessage.Notification(method, paramsOf(node));
        }
        throw new JsonRpcException(INVALID_REQUEST, "消息既不是请求、响应也不是通知");
    }

    /** 把 id 规范化为 Long（数字）或 String（文本），保证请求/响应匹配时 equals 一致。 */
    private static Object normalizeId(JsonNode idNode) {
        if (idNode.isNumber()) {
            if (idNode.canConvertToLong()) {
                return idNode.longValue();
            }
            return idNode.doubleValue();
        }
        if (idNode.isTextual()) {
            return idNode.asText();
        }
        if (idNode.isBoolean()) {
            return idNode.asBoolean();
        }
        return idNode.asText();
    }

    private static Map<String, Object> paramsOf(JsonNode node) {
        JsonNode params = node.get("params");
        if (params == null || params.isNull()) {
            return Map.of();
        }
        if (!params.isObject()) {
            throw new JsonRpcException(INVALID_REQUEST, "params 必须是对象");
        }
        return toJavaObject(params);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toJavaObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return (Map<String, Object>) MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    /** 把任意 JSON 值转换为 Java 对象（Map/List/基本类型）。 */
    private static Object toJavaValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) {
            if (node.canConvertToLong()) {
                return node.longValue();
            }
            return node.doubleValue();
        }
        if (node.isBoolean()) return node.asBoolean();
        if (node.isTextual()) return node.asText();
        if (node.isArray() || node.isObject()) {
            return MAPPER.convertValue(node, Object.class);
        }
        return node.asText();
    }
}
