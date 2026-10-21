package dev.zencode.provider;

import java.util.function.Consumer;

public interface ChatProvider extends AutoCloseable {
    /**
     * 统一的流式请求入口。
     * @param request 统一的请求模型，携带历史、可选当前用户消息、system prompt 与工具 Schema
     * @param eventConsumer 增量与唯一终端事件消费者
     * @return 流式句柄
     */
    StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> eventConsumer);

    /** 获取 Provider 协议来源标识。 */
    String source();

    @Override
    default void close() {
        // 默认空实现，保持既有实现兼容
    }
}
