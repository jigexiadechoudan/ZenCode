package dev.zencode.context;

import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.SeedType;
import dev.zencode.config.ContextConfig;
import dev.zencode.prompt.RequestAssembler;
import dev.zencode.prompt.SystemPromptBuilder;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolResultContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ContextCompressorTest {

    @TempDir
    Path tempDir;

    private static final String SUMMARY = "<summary>压缩摘要</summary>";

    private ContextCompressor compressor(ContextConfig config, ScriptedProvider provider) {
        ToolResultSpooler spooler = new ToolResultSpooler(tempDir, config);
        SummaryGenerator generator = new SummaryGenerator(provider, Duration.ofSeconds(2));
        return new ContextCompressor(spooler, generator, config);
    }

    /** 结构化轮：用户文本 + 助手文本 + 工具结果。 */
    private static ConversationTurn toolTurn(String user, String content) {
        return new ConversationTurn(List.of(
                new ConversationContent.UserText(user),
                new ConversationContent.AssistantText("处理中"),
                new ConversationContent.ToolResultBlock(
                        new ToolResultContent(ToolResult.success("c1", "read", content), "c1", "read"))),
                null, null);
    }

    @Test
    void belowThresholdDoesNotCompact() {
        ScriptedProvider provider = new ScriptedProvider(List.of(new StreamEvent.TextDelta(SUMMARY), completed()));
        ContextConfig config = new ContextConfig(200000, 0.8, 4096, 32768, 3);
        ContextCompressor c = compressor(config, provider);
        Conversation conversation = new Conversation();
        conversation.commit(toolTurn("小对话", "小内容"));

        c.prepareForRequest(conversation);

        assertEquals(1, conversation.size(), "未达阈值不应压缩");
        assertFalse(c.isCircuitOpen());
    }

    @Test
    void reachingThresholdCompactsToEnvSummaryBoundary() {
        ScriptedProvider provider = new ScriptedProvider(List.of(new StreamEvent.TextDelta(SUMMARY), completed()));
        ContextConfig config = new ContextConfig(100, 0.8, 4096, 32768, 3);
        ContextCompressor c = compressor(config, provider);
        Conversation conversation = new Conversation();
        conversation.commit(ConversationTurn.seedTextOnly(SeedType.ENVIRONMENT, "环境快照轮次"));
        conversation.commit(toolTurn("请读取文件", "文件内容" + "a".repeat(200)));
        conversation.commit(toolTurn("继续", "更多内容" + "b".repeat(200)));

        long before = TokenEstimator.estimateHistory(conversation.snapshot());
        c.prepareForRequest(conversation);

        List<ConversationTurn> turns = conversation.snapshot();
        assertEquals(3, turns.size(), "应为 [环境][摘要][边界]");
        assertTrue(turns.get(0).contents().get(0).toString().contains("环境快照轮次"),
                "环境轮应保留: " + turns.get(0));
        assertTrue(TokenEstimator.estimateHistory(turns) < before, "压缩后 Token 应下降");
        assertFalse(c.isCircuitOpen());
    }

    @Test
    void compressionRetainsEveryTypedSeed() {
        ScriptedProvider provider = new ScriptedProvider(List.of(new StreamEvent.TextDelta(SUMMARY), completed()));
        ContextCompressor compressor = compressor(new ContextConfig(100, 0.8, 4096, 32768, 3), provider);
        Conversation conversation = new Conversation();
        conversation.commit(ConversationTurn.seedTextOnly(SeedType.ENVIRONMENT, "环境"));
        conversation.commit(ConversationTurn.seedTextOnly(SeedType.PROJECT_INSTRUCTIONS, "项目指令"));
        conversation.commit(ConversationTurn.seedTextOnly(SeedType.MEMORY, "记忆"));
        conversation.commit(ConversationTurn.seedTextOnly(SeedType.SKILL_SUMMARY, "技能摘要"));
        conversation.commit(toolTurn("任务", "x".repeat(300)));
        assertTrue(compressor.compactOnce(conversation).ok());
        assertEquals(List.of(SeedType.ENVIRONMENT, SeedType.PROJECT_INSTRUCTIONS,
                        SeedType.MEMORY, SeedType.SKILL_SUMMARY),
                conversation.snapshot().stream().limit(4).map(ConversationTurn::seedType).toList());
        var request = new RequestAssembler(tempDir, "test",
                SystemPromptBuilder.defaults().build(), List.of())
                .assemble(conversation, 2, false, null);
        assertTrue(request.history().stream().anyMatch(t -> t.userMessage() != null
                && t.userMessage().text().contains("项目指令")));
        assertTrue(request.history().stream().anyMatch(t -> t.userMessage() != null
                && t.userMessage().text().contains("记忆")));
    }

    @Test
    void noEnvironmentTurnCompactsToSummaryBoundary() {
        ScriptedProvider provider = new ScriptedProvider(List.of(new StreamEvent.TextDelta(SUMMARY), completed()));
        ContextConfig config = new ContextConfig(20, 0.8, 4096, 32768, 3);
        ContextCompressor c = compressor(config, provider);
        Conversation conversation = new Conversation();
        conversation.commit(toolTurn("请读取文件", "文件内容abc"));
        conversation.commit(toolTurn("继续", "更多内容"));

        c.prepareForRequest(conversation);

        assertEquals(2, conversation.size(), "无环境轮时应为 [摘要][边界]");
        String second = conversation.snapshot().get(1).contents().get(0).toString();
        assertTrue(second.contains(ContextCompressor.BOUNDARY_MESSAGE), second);
    }

    @Test
    void summaryFailureCountsAndBreaksCircuit() {
        ScriptedProvider provider = new ScriptedProvider(List.of(new StreamEvent.Failed("失败")));
        ContextConfig config = new ContextConfig(1000, 0.8, 4096, 32768, 2);
        ContextCompressor c = compressor(config, provider);
        Conversation conversation = new Conversation();
        conversation.commit(toolTurn("任务", "大结果内容很大很大"));

        ContextCompressor.CompactResult first = c.compactOnce(conversation);
        assertFalse(first.ok());
        assertFalse(c.isCircuitOpen());

        ContextCompressor.CompactResult second = c.compactOnce(conversation);
        assertFalse(second.ok());
        assertTrue(c.isCircuitOpen(), "连续失败达上限应熔断");
        assertEquals(1, conversation.size(), "压缩失败不应改写历史");
    }

    @Test
    void manualCompactBypassesCircuitAndResetsOnSuccess() {
        ScriptedProvider provider = new ScriptedProvider(List.of(new StreamEvent.Failed("失败")));
        ContextConfig config = new ContextConfig(1000, 0.8, 4096, 32768, 2);
        ContextCompressor c = compressor(config, provider);
        Conversation conversation = new Conversation();
        conversation.commit(toolTurn("任务", "内容"));
        c.compactOnce(conversation);
        c.compactOnce(conversation);
        assertTrue(c.isCircuitOpen());

        // 换成功 provider，手动触发应成功并重置熔断
        ScriptedProvider okProvider = new ScriptedProvider(List.of(new StreamEvent.TextDelta(SUMMARY), completed()));
        ContextCompressor c2 = compressor(config, okProvider);
        c2.compactOnce(conversation);
        assertFalse(c2.isCircuitOpen(), "手动成功应重置熔断");
    }

    @Test
    void automaticSkipsWhenCircuitOpen() {
        ScriptedProvider provider = new ScriptedProvider(List.of(new StreamEvent.Failed("失败")));
        ContextConfig config = new ContextConfig(10, 0.8, 4096, 32768, 1);
        ContextCompressor c = compressor(config, provider);
        Conversation conversation = new Conversation();
        conversation.commit(toolTurn("任务", "内容内容"));
        c.prepareForRequest(conversation);
        assertTrue(c.isCircuitOpen());

        int sizeBefore = conversation.size();
        c.prepareForRequest(conversation);
        assertEquals(sizeBefore, conversation.size(), "熔断后自动压缩应跳过，不再次调用");
    }

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
