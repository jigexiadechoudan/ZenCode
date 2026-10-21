package dev.zencode.team;

import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 队员 transcript 落盘/恢复测试。
 */
class TeamTranscriptStoreTest {

    @TempDir
    Path tempDir;

    private TeamTranscriptStore newStore() {
        return new TeamTranscriptStore(tempDir.resolve("team"));
    }

    private ConversationTurn turn(String text) {
        return new ConversationTurn(List.of(new ConversationContent.UserText(text)), null, null);
    }

    @Test
    void appendWritesOnlyNewTurns() throws Exception {
        TeamTranscriptStore store = newStore();
        Conversation c1 = new Conversation();
        c1.commit(turn("第一轮"));
        c1.commit(turn("第二轮"));
        store.append("m-1", c1.snapshot());
        int lines1 = (int) Files.lines(store.transcriptFile("m-1")).count();
        assertEquals(2, lines1);

        c1.commit(turn("第三轮"));
        store.append("m-1", c1.snapshot());
        int lines2 = (int) Files.lines(store.transcriptFile("m-1")).count();
        assertEquals(3, lines2, "append 应增量写入新轮次");
    }

    @Test
    void loadRoundTripsTurns() {
        TeamTranscriptStore store = newStore();
        Conversation source = new Conversation();
        source.commit(turn("你好"));
        source.commit(turn("继续"));
        store.append("m-1", source.snapshot());

        Conversation loaded = store.load("m-1");
        assertEquals(2, loaded.size());
        ConversationContent first = loaded.snapshot().get(0).contents().get(0);
        assertInstanceOf(ConversationContent.UserText.class, first);
        assertEquals("你好", ((ConversationContent.UserText) first).text());
    }

    @Test
    void loadMissingReturnsEmptyConversation() {
        TeamTranscriptStore store = newStore();
        Conversation loaded = store.load("m-9");
        assertTrue(loaded.snapshot().isEmpty());
    }

    @Test
    void corruptLinesAreSkipped() throws Exception {
        TeamTranscriptStore store = newStore();
        Conversation source = new Conversation();
        source.commit(turn("有效轮次"));
        store.append("m-1", source.snapshot());
        Files.writeString(store.transcriptFile("m-1"),
                Files.readString(store.transcriptFile("m-1")) + "bad line\n");

        Conversation loaded = store.load("m-1");
        assertEquals(1, loaded.size());
    }

    @Test
    void clearRemovesFile() {
        TeamTranscriptStore store = newStore();
        Conversation source = new Conversation();
        source.commit(turn("x"));
        store.append("m-1", source.snapshot());
        store.clear("m-1");
        assertFalse(Files.exists(store.transcriptFile("m-1")));
        assertTrue(store.load("m-1").snapshot().isEmpty());
    }

    @Test
    void agentIdTraversalRejected() {
        TeamTranscriptStore store = newStore();
        assertThrows(TeamException.class, () -> store.transcriptFile("../evil"));
        assertThrows(TeamException.class, () -> store.transcriptFile("a/b"));
        assertThrows(TeamException.class, () -> store.transcriptFile(".."));
    }
}
