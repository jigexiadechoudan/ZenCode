package dev.zencode.prompt;

import dev.zencode.chat.ChatMessage;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.Role;
import dev.zencode.provider.ProviderRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import dev.zencode.prompt.PlanReminderPolicy.Variant;

/** F2/F3/F4：三通道组装——环境首轮快照、Plan reminder 瞬时通道、任务/工具通道。 */
class RequestAssemblerTest {

    private RequestAssembler newAssembler() {
        return AssemblerFactory.assembler();
    }

    @Test
    void environmentCommittedOnceAsFirstTurn() {
        Conversation conversation = new Conversation();
        RequestAssembler assembler = newAssembler();
        ProviderRequest first = assembler.assemble(conversation, 1, false, "任务");
        ProviderRequest second = assembler.assemble(conversation, 2, false, null);

        assertEquals(1, first.history().size(), "首轮 history 应含环境轮");
        assertTrue(first.history().get(0).hasUserText());
        assertTrue(first.history().get(0).userMessage().text().contains("<system-reminder>"),
                "环境轮应被 system-reminder 包裹");
        assertTrue(assembler.isSeeded(conversation));
        assertEquals(1, second.history().size(), "环境只探测一次，不随请求增长");
    }

    @Test
    void firstRoundCarriesCurrentUserMessage_ButNotLaterRounds() {
        Conversation conversation = new Conversation();
        RequestAssembler assembler = newAssembler();

        ProviderRequest first = assembler.assemble(conversation, 1, false, "初始任务");
        assertTrue(first.hasCurrentUserMessage());
        assertEquals("初始任务", first.currentUserMessage().text());

        ProviderRequest second = assembler.assemble(conversation, 2, false, null);
        assertFalse(second.hasCurrentUserMessage());
    }

@Test
    void planModeInjectsRoundAppropriateReminderAsTransientOnly() {
        Conversation conversation = new Conversation();
        RequestAssembler assembler = newAssembler();

        ProviderRequest first = assembler.assemble(conversation, 1, true, "规划");
        assertEquals(PlanReminderPolicy.Variant.FULL, PlanReminderPolicy.variantForRound(1));
        assertEquals(1, first.transientReminders().size(), "首轮注入一次完整 reminder");
        assertTrue(first.transientReminders().get(0).text().contains("计划模式"));

        // 精简版轮次只注入一次精简 reminder，且不残留到历史
        ProviderRequest second = assembler.assemble(conversation, 2, true, null);
        assertEquals(Variant.CONCISE, PlanReminderPolicy.variantForRound(2));
        assertEquals(1, second.transientReminders().size());
        assertEquals(1, conversation.size(), "瞬时 reminder 不写入会话历史，历史只有环境轮");
    }

    @Test
    void normalModeInjectsNoPlanReminder() {
        Conversation conversation = new Conversation();
        RequestAssembler assembler = newAssembler();
        ProviderRequest req = assembler.assemble(conversation, 1, false, "普通任务");
        assertFalse(req.hasTransientReminders());
    }

    @Test
    void carriesSystemPromptAndToolSchemas() {
        Conversation conversation = new Conversation();
        RequestAssembler assembler = newAssembler();
        ProviderRequest req = assembler.assemble(conversation, 1, false, "任务");
        assertNotNull(req.systemPrompt());
        assertFalse(req.systemPrompt().isBlank());
        assertNotNull(req.toolSchemas());
    }

    @Test
    void blankCurrentUserMessageTreatedAsAbsent() {
        Conversation conversation = new Conversation();
        RequestAssembler assembler = newAssembler();
        ProviderRequest req = assembler.assemble(conversation, 1, false, "  ");
        assertFalse(req.hasCurrentUserMessage());
    }

    @Test
    void setSystemPromptOverridesPromptText() {
        Conversation conversation = new Conversation();
        RequestAssembler assembler = newAssembler();
        assembler.setSystemPrompt(new SystemPrompt("覆盖后的提示词"));
        ProviderRequest req = assembler.assemble(conversation, 1, false, "任务");
        assertTrue(req.systemPrompt().contains("覆盖后的提示词"));
    }

    @Test
    void setSystemPromptRejectsNull() {
        RequestAssembler assembler = newAssembler();
        assertThrows(NullPointerException.class, () -> assembler.setSystemPrompt(null));
    }

    @Test
    void mailSupplierMessagesInjectedAsTransientReminders() {
        Conversation conversation = new Conversation();
        RequestAssembler assembler = newAssembler();
        assembler.setMailSupplier(() -> List.of("队员 A: 任务完成", "队员 B: 等待指令"));
        ProviderRequest req = assembler.assemble(conversation, 1, false, "任务");
        assertTrue(req.hasTransientReminders());
        List<String> texts = req.transientReminders().stream().map(ChatMessage::text).toList();
        assertEquals(2, texts.size());
        assertTrue(texts.get(0).contains("队员 A: 任务完成"));
        assertTrue(texts.get(1).contains("队员 B: 等待指令"));
    }

    @Test
    void mailSupplierNullResultInjectsNothing() {
        Conversation conversation = new Conversation();
        RequestAssembler assembler = newAssembler();
        assembler.setMailSupplier(() -> null);
        ProviderRequest req = assembler.assemble(conversation, 1, false, "任务");
        assertFalse(req.hasTransientReminders());
    }
}