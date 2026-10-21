package dev.zencode.context;

import dev.zencode.chat.ConversationTurn;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SummaryGeneratorCancellationTest {
    private static final List<ConversationTurn> HISTORY =
            List.of(ConversationTurn.userTextOnly("摘要历史"));

    @Test
    void timeoutDropsLateCallbacksSoPhysicalReaderCanFinish() throws Exception {
        LateEventsProvider provider = new LateEventsProvider();
        try {
            var result = new SummaryGenerator(provider, Duration.ofMillis(30)).generate(HISTORY);
            assertFalse(result.ok());
            assertEquals("摘要生成超时", result.error());
            provider.physical.get(2, TimeUnit.SECONDS);
        } finally {
            provider.cleanup();
        }
    }

    @Test
    void interruptionDropsLateCallbacksSoPhysicalReaderCanFinish() throws Exception {
        LateEventsProvider provider = new LateEventsProvider();
        CompletableFuture<SummaryGenerator.SummaryResponse> result = new CompletableFuture<>();
        Thread summary = Thread.ofVirtual().start(() -> result.complete(
                new SummaryGenerator(provider, Duration.ofSeconds(10)).generate(HISTORY)));
        try {
            assertTrue(provider.started.await(2, TimeUnit.SECONDS));
            summary.interrupt();
            var response = result.get(2, TimeUnit.SECONDS);
            assertFalse(response.ok());
            assertEquals("摘要生成被中断", response.error());
            provider.physical.get(2, TimeUnit.SECONDS);
        } finally {
            summary.interrupt();
            provider.cleanup();
            summary.join(2000);
        }
    }

    private static final class LateEventsProvider implements ChatProvider {
        final CompletableFuture<Void> logical = new CompletableFuture<>();
        final CompletableFuture<Void> physical = new CompletableFuture<>();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        volatile Thread reader;

        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
            reader = Thread.ofVirtual().start(() -> {
                try {
                    cancelled.await();
                    // 模拟取消后已进入回调的事件，数量超过桥接队列容量。
                    for (int i = 0; i < 128; i++) consumer.accept(new StreamEvent.TextDelta("late"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    physical.complete(null);
                }
            });
            started.countDown();
            return new StreamHandle() {
                @Override public void cancel() {
                    logical.complete(null);
                    cancelled.countDown();
                }
                @Override public CompletionStage<Void> completion() { return logical; }
                @Override public CompletionStage<Void> physicalCompletion() { return physical; }
            };
        }

        @Override public String source() { return "openai"; }

        void cleanup() throws InterruptedException {
            cancelled.countDown();
            if (reader != null) {
                reader.interrupt();
                reader.join(2000);
            }
        }
    }
}
