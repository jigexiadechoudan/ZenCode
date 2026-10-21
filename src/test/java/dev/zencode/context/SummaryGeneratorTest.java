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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SummaryGeneratorTest {

    private static final List<ConversationTurn> HISTORY =
            List.of(ConversationTurn.userTextOnly("历史消息"));

    @Test
    void extractsSummaryBetweenTags() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                new StreamEvent.TextDelta("分析草稿..."),
                new StreamEvent.TextDelta("<summary>正式摘要内容</summary>"),
                completed()));
        SummaryGenerator generator = new SummaryGenerator(provider, Duration.ofSeconds(2));

        SummaryGenerator.SummaryResponse result = generator.generate(HISTORY);

        assertTrue(result.ok());
        assertEquals("正式摘要内容", result.summary());
    }

    @Test
    void fallsBackToFullTextWhenNoTags() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                new StreamEvent.TextDelta("无标签的全文摘要"),
                completed()));
        SummaryGenerator generator = new SummaryGenerator(provider, Duration.ofSeconds(2));

        SummaryGenerator.SummaryResponse result = generator.generate(HISTORY);

        assertTrue(result.ok());
        assertEquals("无标签的全文摘要", result.summary());
    }

    @Test
    void failedEventReturnsNotOk() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                new StreamEvent.Failed("摘要生成失败")));
        SummaryGenerator generator = new SummaryGenerator(provider, Duration.ofSeconds(2));

        SummaryGenerator.SummaryResponse result = generator.generate(HISTORY);

        assertFalse(result.ok());
        assertTrue(result.error().contains("摘要生成失败"));
    }

    @Test
    void requestCarriesNoToolSchemaAndNoCurrentUserMessage() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                new StreamEvent.TextDelta("<summary>x</summary>"),
                completed()));
        SummaryGenerator generator = new SummaryGenerator(provider, Duration.ofSeconds(2));

        generator.generate(HISTORY);

        ProviderRequest request = generator.lastRequest();
        assertNotNull(request);
        assertTrue(request.toolSchemas().isEmpty());
        assertFalse(request.hasCurrentUserMessage());
        assertTrue(request.history().size() == HISTORY.size());
    }

    @Test
    void emptyHistoryReturnsNotOk() {
        ScriptedProvider provider = new ScriptedProvider(List.of(completed()));
        SummaryGenerator generator = new SummaryGenerator(provider, Duration.ofSeconds(2));
        SummaryGenerator.SummaryResponse result = generator.generate(List.of());
        assertFalse(result.ok());
    }

    private static StreamEvent completed() {
        return new StreamEvent.Completed(new dev.zencode.provider.ProviderResponse(
                "", "", List.of(), dev.zencode.provider.ProviderStopReason.END_TURN, null, null));
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
                public void cancel() {
                    completion.complete(null);
                }

                @Override
                public CompletionStage<Void> completion() {
                    return completion;
                }
            };
        }

        @Override
        public String source() {
            return "test";
        }
    }
}
