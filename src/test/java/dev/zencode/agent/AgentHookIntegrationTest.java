package dev.zencode.agent;

import dev.zencode.agent.execution.ToolInterceptorChain;
import dev.zencode.chat.Conversation;
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
import dev.zencode.chat.Usage;
import dev.zencode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hook 与 Agent 主循环的集成：turn_start/turn_end/pre_send/post_receive/error 事件触发。
 */
class AgentHookIntegrationTest {

    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(8);

    private static Hook promptHook(HookEvent event, String message) {
        return new Hook(event.name().toLowerCase(), event, null, new PromptAction(message), false, false, false);
    }

    @Test
    void 正常完成触发turn和消息级事件() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(new StreamEvent.TextDelta("答复"));
            round.emit(completed("最终答复"));
            round.completion.complete(null);
        }));
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(
                promptHook(HookEvent.TURN_START, "EVENT_TURN_START"),
                promptHook(HookEvent.PRE_SEND, "EVENT_PRE_SEND"),
                promptHook(HookEvent.POST_RECEIVE, "EVENT_POST_RECEIVE"),
                promptHook(HookEvent.TURN_END, "EVENT_TURN_END")));
        Agent agent = new Agent(provider, new ToolRegistry(), Path.of("."),
                ToolInterceptorChain.empty(), "unknown", null, null, engine);
        Conversation conversation = new Conversation();

        runAndDrain(agent, conversation, "你好", timeout(2000));

        assertTrue(engine.injectedPrompts().containsAll(List.of(
                        "EVENT_TURN_START", "EVENT_PRE_SEND", "EVENT_POST_RECEIVE", "EVENT_TURN_END")),
                engine.injectedPrompts().toString());
    }

    @Test
    void 模型失败触发error事件() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(round -> {
            round.emit(new StreamEvent.Failed("模型失败"));
        }));
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(promptHook(HookEvent.ERROR, "EVENT_ERROR")));
        Agent agent = new Agent(provider, new ToolRegistry(), Path.of("."),
                ToolInterceptorChain.empty(), "unknown", null, null, engine);
        Conversation conversation = new Conversation();

        List<AgentEvent> events = runAndDrain(agent, conversation, "失败请求", timeout(2000));

        assertNotNull(firstOf(events, AgentEvent.Error.class));
        assertTrue(engine.injectedPrompts().contains("EVENT_ERROR"),
                engine.injectedPrompts().toString());
    }

    // ---- 测试工具 ----

    private static List<AgentEvent> runAndDrain(Agent agent, Conversation conversation,
                                                String message, AgentRunOptions options) throws Exception {
        AgentRun run = agent.run(conversation, message, options);
        List<AgentEvent> events = new ArrayList<>();
        long deadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            AgentEvent event = run.pollEvent(Duration.ofMillis(500));
            if (event == null) {
                if (run.completion().isDone()) {
                    events.add(run.takeEvent());
                    break;
                }
                continue;
            }
            events.add(event);
            if (event instanceof AgentEvent.Terminated) break;
        }
        assertNotNull(firstOf(events, AgentEvent.Terminated.class), "应在超时前收到唯一终止事件");
        return events;
    }

    private static AgentRunOptions timeout(int millis) {
        return new AgentRunOptions(false, 20, Duration.ofMillis(millis), 64);
    }

    private static StreamEvent completed(String text) {
        return new StreamEvent.Completed(
                new ProviderResponse("", text, List.of(), ProviderStopReason.END_TURN, Usage.unknown(), null));
    }

    private static <T extends AgentEvent> T firstOf(List<AgentEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    @FunctionalInterface
    private interface Round {
        void play(EmittingContext ctx);
    }

    private static final class EmittingContext {
        final Consumer<StreamEvent> emit;
        final CompletableFuture<Void> completion;

        EmittingContext(Consumer<StreamEvent> emit, CompletableFuture<Void> completion) {
            this.emit = emit;
            this.completion = completion;
        }

        void emit(StreamEvent event) {
            emit.accept(event);
        }
    }

    private static final class ScriptedProvider implements ChatProvider {
        final List<Round> rounds;
        final List<ProviderRequest> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger requestCount = new AtomicInteger();

        ScriptedProvider(List<Round> rounds) {
            this.rounds = rounds;
        }

        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
            requests.add(request);
            int index = requestCount.getAndIncrement();
            Round round = rounds.get(Math.min(index, rounds.size() - 1));
            CompletableFuture<Void> completion = new CompletableFuture<>();
            round.play(new EmittingContext(consumer, completion));
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
            return "anthropic";
        }
    }
}
