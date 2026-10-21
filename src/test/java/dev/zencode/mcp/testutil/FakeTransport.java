package dev.zencode.mcp.testutil;

import dev.zencode.mcp.transport.Transport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * 内存假传输：记录发送的请求，测试手动按 id 回包。
 */
public final class FakeTransport implements Transport {
    public final BlockingQueue<String> requestQueue = new LinkedBlockingQueue<>();
    public final List<String> sentAll = new ArrayList<>();
    public volatile Consumer<String> handler;

    @Override
    public void send(String message) {
        sentAll.add(message);
        requestQueue.add(message);
    }

    @Override
    public void setMessageHandler(Consumer<String> handler) {
        this.handler = handler;
    }

    @Override
    public boolean isAlive() {
        return true;
    }

    @Override
    public void close() {
    }

    public void reply(String json) {
        handler.accept(json);
    }
}
