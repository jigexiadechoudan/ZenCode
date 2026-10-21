package dev.zencode.context;

import dev.zencode.chat.Conversation;
import dev.zencode.config.ContextConfig;
import dev.zencode.hook.Hook;
import dev.zencode.hook.HookEngine;
import dev.zencode.hook.HookEvent;
import dev.zencode.hook.PromptAction;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8：ContextCompressor 压缩成功/失败时触发 compact（经注入缓存观察）。
 */
class ContextCompressorHookTest {

    @TempDir
    Path tempDir;

    private static final String SUMMARY = "<summary>压缩摘要</summary>";

    private static Hook promptHook(String id, HookEvent event, String text) {
        return new Hook(id, event, null, new PromptAction(text), false, false, false);
    }

    private ContextCompressor compressor(HookEngine engine, ScriptedProvider provider, ContextConfig config) {
        ToolResultSpooler spooler = new ToolResultSpooler(tempDir, config);
        SummaryGenerator generator = new SummaryGenerator(provider, Duration.ofSeconds(2));
        ContextCompressor c = new ContextCompressor(spooler, generator, config);
        c.setHookEngine(engine);
        return c;
    }

    @Test
    void successfulCompactTriggersEvent() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(promptHook("c", HookEvent.COMPACT, "[COMPACT]")));
        ScriptedProvider provider = new ScriptedProvider(List.of(
                new StreamEvent.TextDelta(SUMMARY), completed()));
        ContextConfig config = new ContextConfig(20, 0.8, 4096, 32768, 3);
        ContextCompressor c = compressor(engine, provider, config);
        Conversation conversation = new Conversation();
        conversation.commit(dev.zencode.chat.ConversationTurn.userTextOnly("环境快照轮次"));
        conversation.commit(new dev.zencode.chat.ConversationTurn(List.of(
                new dev.zencode.chat.ConversationContent.UserText("请读取文件"),
                new dev.zencode.chat.ConversationContent.AssistantText("处理中"),
                new dev.zencode.chat.ConversationContent.ToolResultBlock(
                        new dev.zencode.tool.ToolResultContent(
                                dev.zencode.tool.ToolResult.success("c1", "read", "内容" + "a".repeat(200)),
                                "c1", "read"))),
                null, null));

        dev.zencode.context.ContextCompressor.CompactResult result = c.compactOnce(conversation);

        assertTrue(result.ok());
        assertTrue(engine.injectedPrompts().contains("[COMPACT]"), "压缩成功应触发 compact 事件");
    }

    @Test
    void failedCompactAlsoTriggersEvent() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(promptHook("c", HookEvent.COMPACT, "[COMPACT]")));
        ScriptedProvider provider = new ScriptedProvider(List.of(new StreamEvent.Failed("失败")));
        ContextConfig config = new ContextConfig(1000, 0.8, 4096, 32768, 2);
        ContextCompressor c = compressor(engine, provider, config);
        Conversation conversation = new Conversation();
        conversation.commit(dev.zencode.chat.ConversationTurn.userTextOnly("环境快照轮次"));

        ContextCompressor.CompactResult result = c.compactOnce(conversation);

        assertFalse(result.ok());
        assertTrue(engine.injectedPrompts().contains("[COMPACT]"), "压缩失败也应触发 compact 事件");
    }

    @Test
    void withoutEngineNoEvent() {
        ContextCompressor c = compressor(null,
                new ScriptedProvider(List.of(new StreamEvent.Failed("失败"))),
                new ContextConfig(1000, 0.8, 4096, 32768, 2));
        Conversation conversation = new Conversation();
        conversation.commit(dev.zencode.chat.ConversationTurn.userTextOnly("环境快照轮次"));

        c.compactOnce(conversation);
        // 不抛异常即通过
        assertTrue(true);
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
