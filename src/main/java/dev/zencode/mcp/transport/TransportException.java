package dev.zencode.mcp.transport;

/**
 * 传输层异常：发送失败（子进程退出、网络错误、HTTP 超时等）。
 * <p>
 * 上层 {@code McpClient} / {@code McpToolWrapper} 捕获后转为工具失败结果，
 * 不向 Agent 抛出未捕获异常。
 * </p>
 */
public final class TransportException extends RuntimeException {

    public TransportException(String message) {
        super(message);
    }

    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
