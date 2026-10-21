package dev.zencode.mcp.transport;

import java.util.function.Consumer;

/**
 * MCP 传输层抽象：向 Server 发送一条完整 JSON-RPC 消息、接收来自 Server 的消息、关闭连接。
 * <p>
 * 发送与接收以「完整 JSON 消息」为单位，消息边界由各实现负责（stdio 按行分隔，
 * http 单次 POST 往返）。入站消息通过 {@link #setMessageHandler} 注册的回调交给上层
 * （{@code McpClient} 在回调中做请求-响应匹配），传输层不感知消息 id。
 * </p>
 * <p>
 * 实现约定：stdio 为异步（后台读线程驱动回调）；http 为同步（在 send 内完成回调）。
 * 上层必须保证 handler 的线程安全性。
 * </p>
 */
public interface Transport extends AutoCloseable {

    /**
     * 发送一条完整 JSON 消息。
     *
     * @throws TransportException 发送失败（子进程退出、网络错误、超时等）
     */
    void send(String message);

    /**
     * 注册入站消息回调。应在开始收发前调用一次。
     * 回调由传输层线程触发，可能并发调用（http 的多个 send 各自触发）。
     */
    void setMessageHandler(Consumer<String> handler);

    /** 传输是否仍可用。 */
    boolean isAlive();

    /** 释放资源：关闭子进程 / 网络连接。多次调用安全。 */
    @Override
    void close();
}
