package dev.zencode.mcp.jsonrpc;

/**
 * JSON-RPC 2.0 标准错误码（规范保留的预定义错误码）。
 */
public enum JsonRpcErrorCode {
    /** 无效 JSON，或无法解析的请求（-32700）。 */
    PARSE_ERROR(-32700, "解析错误：无效 JSON"),
    /** 请求对象不是合法的 JSON-RPC 请求（-32600）。 */
    INVALID_REQUEST(-32600, "无效请求"),
    /** 方法不存在或不可用（-32601）。 */
    METHOD_NOT_FOUND(-32601, "方法不存在"),
    /** 方法参数无效（-32602）。 */
    INVALID_PARAMS(-32602, "参数无效"),
    /** 服务器内部错误（-32603）。 */
    INTERNAL_ERROR(-32603, "内部错误");

    private final int code;
    private final String defaultMessage;

    JsonRpcErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /** JSON-RPC 错误码数值。 */
    public int code() {
        return code;
    }

    /** 该错误码的默认可读消息。 */
    public String defaultMessage() {
        return defaultMessage;
    }
}
