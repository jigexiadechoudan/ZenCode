package dev.zencode.mcp.jsonrpc;

/**
 * JSON-RPC 编解码错误，携带 JSON-RPC 2.0 标准错误码。
 * <p>
 * 解码端遇到无效 JSON、无效请求对象、方法名缺失等编码错误时抛出，
 * 供上层构造规范的 JSON-RPC 错误响应，而不是崩溃或静默丢弃。
 * </p>
 */
public final class JsonRpcException extends RuntimeException {
    private final int code;

    public JsonRpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public JsonRpcException(JsonRpcErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.code = errorCode.code();
    }

    public JsonRpcException(JsonRpcErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.code();
    }

    /** JSON-RPC 标准错误码。 */
    public int code() {
        return code;
    }
}
