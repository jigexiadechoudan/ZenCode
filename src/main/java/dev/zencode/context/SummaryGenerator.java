package dev.zencode.context;

import dev.zencode.chat.ConversationTurn;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 调用 LLM 生成结构化摘要（F3.8）。
 * <p>
 * 复用当前 Provider，但不携带工具 Schema、无当前用户消息。
 * 聚合文本增量直到 Completed/Failed，从响应中提取 {@code <summary>} 区为正式摘要；
 * 缺失标签时尽力取全文。失败/超时返回 {@code ok=false}，不抛异常。
 * </p>
 */
public final class SummaryGenerator {

    private static final long POLL_CHUNK_NANOS = 20_000_000L;

    private final ChatProvider provider;
    private final Duration timeout;
    private volatile ProviderRequest lastRequest;

    public SummaryGenerator(ChatProvider provider, Duration timeout) {
        this.provider = Objects.requireNonNull(provider, "Provider 不能为空");
        this.timeout = Objects.requireNonNull(timeout, "超时不能为空");
    }

    /**
     * 生成整段历史的摘要。
     *
     * @param history 待压缩的完整历史
     * @return 摘要结果；失败时 {@code ok=false} 且携带原因
     */
    public SummaryResponse generate(List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return new SummaryResponse("", false, "无历史可压缩");
        }
        ProviderRequest request = new ProviderRequest(history, List.of(), null,
                SummaryPrompt.systemPrompt(), List.of());
        lastRequest = request;

        ArrayBlockingQueue<StreamEvent> bridge = new ArrayBlockingQueue<>(64);
        AtomicBoolean terminalSeen = new AtomicBoolean(false);
        AtomicBoolean roundClosed = new AtomicBoolean(false);
        Consumer<StreamEvent> consumer = event -> {
            if (event instanceof StreamEvent.Completed || event instanceof StreamEvent.Failed) {
                terminalSeen.set(true);
            }
            try {
                while (!roundClosed.get()) {
                    if (bridge.offer(event, POLL_CHUNK_NANOS, TimeUnit.NANOSECONDS)) return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        StreamHandle handle = null;
        StringBuilder text = new StringBuilder();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            handle = provider.stream(request, consumer);
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return new SummaryResponse("", false, "摘要生成超时");
                }
                StreamEvent event = bridge.poll(Math.min(remaining, POLL_CHUNK_NANOS), TimeUnit.NANOSECONDS);
                if (event == null) {
                    if (handle.completion().toCompletableFuture().isDone()
                            && bridge.isEmpty() && !terminalSeen.get()) {
                        return new SummaryResponse("", false, "Provider 完成但没有终端事件");
                    }
                    continue;
                }
                switch (event) {
                    case StreamEvent.TextDelta delta -> text.append(delta.text());
                    case StreamEvent.Completed completed -> {
                        String summary = extractSummary(text.toString());
                        if (summary.isBlank()) {
                            return new SummaryResponse("", false, "摘要为空");
                        }
                        return new SummaryResponse(summary, true, null);
                    }
                    case StreamEvent.Failed failed -> {
                        return new SummaryResponse("", false, failed.safeMessage());
                    }
                    default -> { /* 忽略 thinking/tool 增量 */ }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SummaryResponse("", false, "摘要生成被中断");
        } finally {
            // 先停止回调入队，再取消网络；否则满队列 reader 可能永远无法退出。
            roundClosed.set(true);
            if (handle != null) {
                try {
                    handle.cancel();
                } catch (RuntimeException ignored) {
                    // 不让资源清理错误覆盖摘要结果。
                }
            }
        }
    }

    /** 提取 {@code <summary>} 与 {@code </summary>} 之间的文本；缺失时返回全文。 */
    private static String extractSummary(String text) {
        int start = text.indexOf("<summary>");
        int end = text.indexOf("</summary>");
        if (start >= 0 && end > start) {
            return text.substring(start + "<summary>".length(), end).trim();
        }
        return text.trim();
    }

    /** 最近一次请求（测试可见，用于断言未携带工具 Schema）。 */
    public ProviderRequest lastRequest() {
        return lastRequest;
    }

    /** 摘要生成结果。 */
    public record SummaryResponse(String summary, boolean ok, String error) {}
}
