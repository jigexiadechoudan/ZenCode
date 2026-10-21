package dev.zencode.agent;

import dev.zencode.chat.Conversation;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AgentProviderBackpressureTest {
    @Test
    void cancellationReleasesReaderBlockedBehindBothEventQueues() throws Exception {
        BackpressuredProvider provider = new BackpressuredProvider();
        Agent agent = new Agent(provider, new ToolRegistry(), Path.of("."));
        AgentRun run = agent.run(new Conversation(), "取消满队列", new AgentRunOptions(
                false, 1, Duration.ofSeconds(5), 1));
        try {
            // 不消费 UI 队列：UserMessage 占满它，首个 delta 卡在 UI 发布，
            // 第二个占满 Provider 桥接队列，第三个则卡在 reader 回调。
            assertTrue(provider.thirdEvent.await(2, TimeUnit.SECONDS));
            assertFalse(provider.physical.isDone());
            run.cancel();

            provider.physical.get(2, TimeUnit.SECONDS);
            assertEquals(AgentTerminationReason.CANCELLED, run.completion().get(2, TimeUnit.SECONDS));
            assertEquals(1, provider.cancelCalls.get());
        } finally {
            run.cancel();
            if (provider.reader != null) {
                provider.reader.interrupt();
                provider.reader.join(2000);
            }
        }
    }

    private static final class BackpressuredProvider implements ChatProvider {
        final CompletableFuture<Void> logical = new CompletableFuture<>();
        final CompletableFuture<Void> physical = new CompletableFuture<>();
        final CountDownLatch thirdEvent = new CountDownLatch(1);
        final AtomicInteger cancelCalls = new AtomicInteger();
        volatile Thread reader;

        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
            reader = Thread.ofVirtual().start(() -> {
                try {
                    consumer.accept(new StreamEvent.TextDelta("a"));
                    consumer.accept(new StreamEvent.TextDelta("b"));
                    thirdEvent.countDown();
                    consumer.accept(new StreamEvent.TextDelta("c"));
                } finally {
                    physical.complete(null);
                }
            });
            return new StreamHandle() {
                @Override public void cancel() {
                    cancelCalls.incrementAndGet();
                    logical.complete(null);
                    // 模拟关闭 HTTP 流：不会中断阻塞于 consumer.accept 的线程。
                }
                @Override public CompletionStage<Void> completion() { return logical; }
                @Override public CompletionStage<Void> physicalCompletion() { return physical; }
            };
        }

        @Override public String source() { return "openai"; }
    }
}
