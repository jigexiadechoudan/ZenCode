package dev.zencode.provider;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可关闭 SSE 的流式句柄实现。
 * <p>
 * 同时跟踪 HTTP 请求 Future、SSE 输入流和完成状态。取消时关闭输入流（解除读取线程阻塞）、
 * 取消 HTTP 请求并完成 completion；幂等，重复取消无副作用。
 * 取消后读取线程不得再向事件消费者发布迟到事件（由 {@link #isCancelled()} 判断）。
 * </p>
 */
public final class HttpStreamHandle implements StreamHandle {
    private final CompletableFuture<?> request;
    private final CompletableFuture<Void> done;
    private final CompletableFuture<Void> physicalDone = new CompletableFuture<>();
    private final AtomicReference<InputStream> input;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean doneCompleted = new AtomicBoolean();

    public HttpStreamHandle(CompletableFuture<?> request, CompletableFuture<Void> done,
                            AtomicReference<InputStream> input) {
        this.request = request;
        this.done = done;
        this.input = input;
    }

    /** 读取开始后把输入流挂载到句柄，供取消时关闭。 */
    public void attachInput(InputStream stream) {
        input.set(stream);
        // Cancellation can win before the HTTP callback has made the input available.
        if (cancelled.get() && input.compareAndSet(stream, null)) closeQuietly(stream);
    }

    /** 是否已被取消。 */
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        request.cancel(true);
        closeQuietly(input.getAndSet(null));
        completeDone();
    }

    /** 读取任务正常或异常结束后调用，完成 completion（幂等）。 */
    public void completeDone() {
        if (doneCompleted.compareAndSet(false, true)) {
            done.complete(null);
        }
    }

    @Override
    public CompletionStage<Void> completion() {
        return done;
    }

    /** Only the provider's outermost processing callback may signal physical cleanup. */
    public void completePhysical() {
        // Ensure logical completion even if a terminal-event consumer itself failed.
        completeDone();
        input.set(null);
        physicalDone.complete(null);
    }

    @Override
    public CompletionStage<Void> physicalCompletion() {
        return physicalDone;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException | RuntimeException ignored) {
            // Cleanup failures must not leak transport details or break logical cancellation.
        }
    }
}
