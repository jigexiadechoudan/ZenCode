package dev.zencode.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationTest {
    @Test
    void commitsCompleteTurnsAndReturnsImmutableSnapshot() {
        Conversation conversation = new Conversation();
        ConversationTurn turn = new ConversationTurn(
                new ChatMessage(Role.USER, "你好"),
                new ChatMessage(Role.ASSISTANT, "你好，有什么可以帮你？"),
                null, Usage.unknown());

        conversation.commit(turn);
        List<ConversationTurn> snapshot = conversation.snapshot();

        assertEquals(List.of(turn), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(turn));
    }

    @Test
    void rejectsBlankMessagesAndWrongRoles() {
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage(Role.USER, "  "));
        assertThrows(IllegalArgumentException.class, () -> new ConversationTurn(
                new ChatMessage(Role.ASSISTANT, "错误"),
                new ChatMessage(Role.USER, "错误"), null, Usage.unknown()));
    }

    @Test
    void replaceAllReplacesEntireHistory() {
        Conversation conversation = new Conversation();
        ConversationTurn oldTurn = ConversationTurn.userTextOnly("旧消息");
        ConversationTurn keepTurn = ConversationTurn.userTextOnly("保留轮");
        ConversationTurn newTurn = ConversationTurn.userTextOnly("摘要轮");
        conversation.commit(oldTurn);
        conversation.commit(keepTurn);

        assertEquals(2, conversation.size());
        conversation.replaceAll(List.of(newTurn));

        assertEquals(1, conversation.size());
        assertEquals(List.of(newTurn), conversation.snapshot());
    }

    @Test
    void replaceAllRejectsNull() {
        Conversation conversation = new Conversation();
        conversation.commit(ConversationTurn.userTextOnly("x"));
        assertThrows(NullPointerException.class, () -> conversation.replaceAll(null));
    }
}
