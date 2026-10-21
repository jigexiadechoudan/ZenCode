package dev.zencode.provider;

import dev.zencode.config.ProviderConfig;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 共享的 Provider 引用：仅在没有活动流时替换客户端。
 * 主 Agent、摘要与记忆组件持有同一实例，下一次请求自动使用新配置。
 */
public final class SwitchableProvider implements ChatProvider {
    private ProviderConfig config;
    private ChatProvider current;
    private int activeStreams;
    private boolean closed;

    public SwitchableProvider(ProviderConfig config) {
        this.config = Objects.requireNonNull(config, "模型配置不能为空");
        this.current = ProviderFactory.create(config);
    }

    /** 仅供内部装配使用；包含凭据，不能序列化到 UI 或日志。 */
    public synchronized ProviderConfig config() {
        return config;
    }

    public synchronized boolean isStreaming() {
        return activeStreams != 0;
    }

    /**
     * 锁内检查空闲、创建候选客户端、执行持久化/元数据回调，最后原子替换。
     * 回调失败时继续保留旧客户端并原样抛出错误；调用者需预先校验元数据更新。
     */
    public synchronized void apply(ProviderConfig next, Runnable updateMetadata) {
        Objects.requireNonNull(next, "模型配置不能为空");
        Objects.requireNonNull(updateMetadata, "配置更新操作不能为空");
        if (closed) throw new IllegalStateException("模型客户端已关闭。");
        if (activeStreams != 0) {
            throw new IllegalStateException("模型请求尚未完全结束，请稍后再应用配置。");
        }
        ChatProvider candidate = ProviderFactory.create(next);
        try {
            updateMetadata.run();
        } catch (RuntimeException | Error failure) {
            closeQuietly(candidate);
            throw failure;
        }
        ChatProvider previous = current;
        current = candidate;
        config = next;
        closeQuietly(previous);
    }

    @Override
    public synchronized String source() {
        return current.source();
    }

    @Override
    public synchronized StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
        if (closed) throw new IllegalStateException("模型客户端已关闭。");
        activeStreams++;
        StreamHandle delegate;
        try {
            delegate = current.stream(request, consumer);
        } catch (RuntimeException | Error failure) {
            releaseStream();
            throw failure;
        }
        CompletableFuture<Void> logical = new CompletableFuture<>();
        CompletableFuture<Void> physical = new CompletableFuture<>();
        delegate.completion().whenComplete((value, failure) -> complete(logical, failure));
        AtomicBoolean released = new AtomicBoolean();
        delegate.physicalCompletion().whenComplete((value, failure) -> {
            if (released.compareAndSet(false, true)) releaseStream();
            complete(physical, failure);
        });
        return new StreamHandle() {
            @Override
            public void cancel() {
                delegate.cancel();
            }

            @Override
            public CompletionStage<Void> completion() {
                // toCompletableFuture().orTimeout()/cancel() 不能修改内部完成状态。
                return logical.minimalCompletionStage();
            }

            @Override
            public CompletionStage<Void> physicalCompletion() {
                return physical.minimalCompletionStage();
            }
        };
    }

    private synchronized void releaseStream() {
        activeStreams--;
        if (closed && activeStreams == 0) closeQuietly(current);
    }

    /** 有活动流时延期释放；不得在取消后仍在收尾的 reader 下关闭客户端。 */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (activeStreams == 0) closeQuietly(current);
    }

    private static void complete(CompletableFuture<Void> future, Throwable failure) {
        if (failure == null) future.complete(null);
        else future.completeExceptionally(failure);
    }

    private static void closeQuietly(ChatProvider provider) {
        try {
            provider.close();
        } catch (RuntimeException ignored) {
            // 已成功切换，不将旧实例的清理异常误报为保存失败；不输出可能含凭据的异常。
        }
    }
}
