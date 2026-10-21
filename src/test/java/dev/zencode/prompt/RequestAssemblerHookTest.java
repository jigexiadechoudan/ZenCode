package dev.zencode.prompt;

import dev.zencode.chat.ChatMessage;
import dev.zencode.chat.Conversation;
import dev.zencode.hook.Hook;
import dev.zencode.hook.HookContext;
import dev.zencode.hook.HookEngine;
import dev.zencode.hook.HookEvent;
import dev.zencode.hook.PromptAction;
import dev.zencode.provider.ProviderRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8：RequestAssembler 把 HookEngine 注入的 prompt 文本作为轮次 reminder 组装进请求。
 */
class RequestAssemblerHookTest {

    private static Hook promptHook(String id, HookEvent event, String text) {
        return new Hook(id, event, null, new PromptAction(text), false, false, false);
    }

    @Test
    void injectedPromptsAppearAsTransientReminders() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(promptHook("p", HookEvent.SESSION_START, "[HOOK-PROMPT]")));
        engine.runHooks(HookEvent.SESSION_START,
                new HookContext("session_start", null, Map.of(), null, "hello", null));

        RequestAssembler assembler = new RequestAssembler(Path.of("."), "test",
                SystemPromptBuilder.defaults().build(), List.of());
        assembler.setHookEngine(engine);
        Conversation conversation = new Conversation();

        ProviderRequest request = assembler.assemble(conversation, 1, false, "任务");

        assertTrue(request.hasTransientReminders(), "注入过 prompt 后请求应带轮次 reminder");
        boolean found = request.transientReminders().stream()
                .anyMatch(m -> m.text().contains("[HOOK-PROMPT]"));
        assertTrue(found, "transient reminder 应包含注入的 prompt 文本");
    }

    @Test
    void multiplePromptsAllInjected() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(
                promptHook("a", HookEvent.SESSION_START, "[A]"),
                promptHook("b", HookEvent.SESSION_START, "[B]")));
        engine.runHooks(HookEvent.SESSION_START,
                new HookContext("session_start", null, Map.of(), null, "hello", null));

        RequestAssembler assembler = new RequestAssembler(Path.of("."), "test",
                SystemPromptBuilder.defaults().build(), List.of());
        assembler.setHookEngine(engine);
        Conversation conversation = new Conversation();

        ProviderRequest request = assembler.assemble(conversation, 1, false, "任务");

        List<String> texts = request.transientReminders().stream().map(ChatMessage::text).toList();
        assertTrue(texts.stream().anyMatch(t -> t.contains("[A]")));
        assertTrue(texts.stream().anyMatch(t -> t.contains("[B]")));
    }

    @Test
    void withoutEngineNoPromptReminders() {
        RequestAssembler assembler = new RequestAssembler(Path.of("."), "test",
                SystemPromptBuilder.defaults().build(), List.of());
        Conversation conversation = new Conversation();

        ProviderRequest request = assembler.assemble(conversation, 1, false, "任务");

        assertFalse(request.hasTransientReminders(), "未注入 Hook 引擎时不应有 prompt reminder");
    }
}
