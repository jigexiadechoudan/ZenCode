package dev.zencode.subagent;

/**
 * 子 Agent 定义解析/校验错误，消息携带出错文件与字段，便于定位。
 * <p>
 * 典型消息形如 {@code <文件>: <字段> 非法/缺失}；加载器捕获后跳过单文件、不阻断整体加载。
 * </p>
 */
public class AgentParseException extends RuntimeException {

    public AgentParseException(String message) {
        super(message);
    }

    public AgentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
