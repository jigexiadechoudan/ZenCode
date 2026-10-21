package dev.zencode.memory;

import dev.zencode.chat.ConversationTurn;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MemoryExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void lastRequestHasNoToolSchemas() {
        ScriptedProvider provider = new ScriptedProvider(List.of(completed()));
        MemoryExtractor extractor = new MemoryExtractor(provider, Duration.ofSeconds(5));

        MemoryStore store = new MemoryStore(tempDir, tempDir, MemoryConfig.defaults());
        extractor.extractAsync("", List.of(), store).join();

        ProviderRequest last = extractor.lastRequest();
        assertNotNull(last);
        assertTrue(last.toolSchemas().isEmpty(), "提取请求不应携带工具 Schema");
    }

    @Test
    void writesByCategory() {
        String response = """
                ### 用户偏好
                用户喜欢浅色主题

                ### 纠正反馈
                用户不喜欢自动缩进

                ### 项目知识
                使用 Java 17

                ### 参考资料
                Spring Boot 文档
                """;

        ScriptedProvider provider = new ScriptedProvider(List.of(
                new StreamEvent.TextDelta(response),
                completed()));
        MemoryExtractor extractor = new MemoryExtractor(provider, Duration.ofSeconds(5));
        // workDir 和 userHome 用不同目录，确保 PROJECT 和 USER 文件不同
        Path userHome = tempDir.resolve("userhome");
        MemoryStore store = new MemoryStore(tempDir, userHome, MemoryConfig.defaults());

        MemoryExtractor.ExtractResult result = extractor.extractAsync("", List.of(), store).join();
        assertTrue(result.ok());

        // 用户偏好 + 纠正反馈 → USER
        String userMem = store.read(MemoryStore.Scope.USER);
        assertTrue(userMem.contains("用户偏好"), "USER 应包含用户偏好");
        assertTrue(userMem.contains("纠正反馈"), "USER 应包含纠正反馈");
        assertTrue(userMem.contains("浅色主题"), "USER 应包含用户偏好内容");

        // 项目知识 + 参考资料 → PROJECT
        String projectMem = store.read(MemoryStore.Scope.PROJECT);
        assertTrue(projectMem.contains("项目知识"), "PROJECT 应包含项目知识");
        assertTrue(projectMem.contains("参考资料"), "PROJECT 应包含参考资料");
        assertTrue(projectMem.contains("Java 17"), "PROJECT 应包含项目知识内容");
    }

    @Test
    void failureReturnsOkFalse() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                new StreamEvent.Failed("API 错误")));
        MemoryExtractor extractor = new MemoryExtractor(provider, Duration.ofSeconds(5));
        MemoryStore store = new MemoryStore(tempDir, tempDir, MemoryConfig.defaults());

        MemoryExtractor.ExtractResult result = extractor.extractAsync("", List.of(), store).join();
        assertFalse(result.ok());
        assertNotNull(result.error());
    }

    @Test
    void noProviderExceptionGraceful() {
        // Provider 在 stream 中抛异常
        ChatProvider brokenProvider = new ChatProvider() {
            @Override
            public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
                throw new RuntimeException("连接失败");
            }

            @Override
            public String source() { return "test"; }
        };
        MemoryExtractor extractor = new MemoryExtractor(brokenProvider, Duration.ofSeconds(5));
        MemoryStore store = new MemoryStore(tempDir, tempDir, MemoryConfig.defaults());

        MemoryExtractor.ExtractResult result = extractor.extractAsync("", List.of(), store).join();
        assertFalse(result.ok());
    }

    @Test
    void timeoutCancelsHandleWithoutCompletingProviderFuture() throws Exception {
        TrackedProvider provider = new TrackedProvider(false);
        MemoryExtractor extractor = new MemoryExtractor(provider, Duration.ofMillis(30));
        MemoryStore store = new MemoryStore(tempDir, tempDir, MemoryConfig.defaults());

        var result = extractor.extractAsync("", List.of(), store).get(2, TimeUnit.SECONDS);

        assertFalse(result.ok());
        assertEquals("记忆提取超时。", result.error());
        assertTrue(provider.cancelled.await(2, TimeUnit.SECONDS));
        assertEquals(1, provider.cancelCalls.get());
        assertFalse(provider.completion.isDone(), "提取超时不得修改 Provider 内部完成 Future");
    }

    @Test
    void synchronousTerminalBeforeStreamReturnsStillCancelsHandle() throws Exception {
        TrackedProvider provider = new TrackedProvider(true);
        MemoryExtractor extractor = new MemoryExtractor(provider, Duration.ofSeconds(5));
        MemoryStore store = new MemoryStore(tempDir, tempDir, MemoryConfig.defaults());

        var result = extractor.extractAsync("", List.of(), store).get(2, TimeUnit.SECONDS);

        assertTrue(result.ok());
        assertTrue(provider.cancelled.await(2, TimeUnit.SECONDS));
        assertEquals(1, provider.cancelCalls.get());
        assertFalse(provider.completion.isDone());
    }

    @Test
    void completionFailureUsesSafeMessageAndCancelsHandle() throws Exception {
        TrackedProvider provider = new TrackedProvider(false);
        MemoryExtractor extractor = new MemoryExtractor(provider, Duration.ofSeconds(5));
        MemoryStore store = new MemoryStore(tempDir, tempDir, MemoryConfig.defaults());
        var future = extractor.extractAsync("", List.of(), store);

        provider.completion.completeExceptionally(new IllegalStateException("private-token-do-not-echo"));
        var result = future.get(2, TimeUnit.SECONDS);

        assertFalse(result.ok());
        assertEquals("记忆提取请求失败，请检查模型配置与网络连接。", result.error());
        assertTrue(provider.cancelled.await(2, TimeUnit.SECONDS));
        assertEquals(1, provider.cancelCalls.get());
    }

    @Test
    void missingStoreSkipsModelRequest() {
        TrackedProvider provider = new TrackedProvider(false);
        MemoryExtractor extractor = new MemoryExtractor(provider, Duration.ofSeconds(5));

        var result = extractor.extractAsync("", List.of(), null).join();

        assertFalse(result.ok());
        assertEquals("未提供记忆存储，已跳过提取。", result.error());
        assertEquals(0, provider.requests.get());
    }

    private static final class TrackedProvider implements ChatProvider {
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        final CountDownLatch cancelled = new CountDownLatch(1);
        final AtomicInteger cancelCalls = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        final boolean synchronousTerminal;

        TrackedProvider(boolean synchronousTerminal) { this.synchronousTerminal = synchronousTerminal; }

        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
            requests.incrementAndGet();
            if (synchronousTerminal) consumer.accept(completed());
            return new StreamHandle() {
                @Override public void cancel() {
                    cancelCalls.incrementAndGet();
                    cancelled.countDown();
                }
                @Override public CompletionStage<Void> completion() { return completion; }
            };
        }

        @Override public String source() { return "test"; }
    }

    // ---- 工具 ----

    private static StreamEvent completed() {
        return new StreamEvent.Completed(new ProviderResponse(
                "", "", List.of(), ProviderStopReason.END_TURN, null, null));
    }

    private static final class ScriptedProvider implements ChatProvider {
        final List<StreamEvent> events;

        ScriptedProvider(List<StreamEvent> events) {
            this.events = events;
        }

        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            for (StreamEvent event : events) {
                consumer.accept(event);
            }
            completion.complete(null);
            return new StreamHandle() {
                @Override
                public void cancel() { completion.complete(null); }

                @Override
                public CompletionStage<Void> completion() { return completion; }
            };
        }

        @Override
        public String source() { return "test"; }
    }
}
