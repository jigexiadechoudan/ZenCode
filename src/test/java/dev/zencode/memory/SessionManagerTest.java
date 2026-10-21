package dev.zencode.memory;

import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.session.SessionLoadResult;
import dev.zencode.session.SessionMeta;
import dev.zencode.session.SessionStore;
import dev.zencode.session.SessionTurnDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void startCreatesNewId() {
        SessionStore store = new SessionStore(tempDir);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, new Conversation());

        SessionMeta meta = manager.start();
        assertNotNull(meta.id());
        assertEquals(meta.id(), manager.currentId());
    }

    @Test
    void savePendingAppendsTurns() {
        SessionStore store = new SessionStore(tempDir);
        Conversation conversation = new Conversation();
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);

        manager.start();
        conversation.commit(ConversationTurn.userTextOnly("第一轮"));
        conversation.commit(ConversationTurn.userTextOnly("第二轮"));

        manager.savePending();

        List<SessionTurnDto> loaded = store.load(manager.currentId());
        assertEquals(2, loaded.size());
    }

    @Test
    void savePendingUpdatesMeta() {
        SessionStore store = new SessionStore(tempDir);
        Conversation conversation = new Conversation();
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);

        manager.start();
        conversation.commit(ConversationTurn.userTextOnly("测试消息"));

        manager.savePending();

        SessionMeta meta = store.readMeta(manager.currentId());
        assertNotNull(meta);
        assertEquals(1, meta.messageCount());
    }

    @Test
    void restoreRecoversHistory() {
        SessionStore store = new SessionStore(tempDir);
        Conversation conversation = new Conversation();
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);

        // 写入一些轮次
        manager.start();
        conversation.commit(ConversationTurn.userTextOnly("历史消息"));
        manager.savePending();

        String firstId = manager.currentId();

        // 清空并恢复
        conversation.replaceAll(List.of());
        SessionLoadResult result = manager.restore(firstId);

        assertFalse(result.turns().isEmpty());
        assertEquals(1, result.turns().size());
        assertEquals(firstId, manager.currentId());
    }

    @Test
    void beginNewClearsHistory() {
        SessionStore store = new SessionStore(tempDir);
        Conversation conversation = new Conversation();
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);

        manager.start();
        conversation.commit(ConversationTurn.userTextOnly("旧会话"));
        String oldId = manager.currentId();

        manager.beginNew();

        assertNotEquals(oldId, manager.currentId());
        assertTrue(conversation.snapshot().isEmpty());
    }

    @Test
    void deleteRejectsCurrentSession() {
        SessionStore store = new SessionStore(tempDir);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, new Conversation());

        manager.start();

        assertThrows(IllegalStateException.class, () -> manager.delete(manager.currentId()));
    }

    @Test
    void deleteRemovesOtherSession() {
        SessionStore store = new SessionStore(tempDir);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, new Conversation());

        // 创建会话 A
        manager.start();
        String idA = manager.currentId();
        manager.savePending();

        // 创建会话 B
        manager.beginNew();
        String idB = manager.currentId();
        manager.savePending();

        // 删除 A（非当前）
        assertTrue(manager.delete(idA));
        assertTrue(store.load(idA).isEmpty());
    }

    @Test
    void roundCountTracking() {
        SessionStore store = new SessionStore(tempDir);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, new Conversation());

        manager.start();
        assertEquals(0, manager.roundCount());

        manager.bumpRound();
        assertEquals(1, manager.roundCount());

        manager.bumpRound();
        assertEquals(2, manager.roundCount());

        manager.resetRoundCount();
        assertEquals(0, manager.roundCount());
    }

    @Test
    void restoreInsertsTimeReminderWhenIdle() throws Exception {
        SessionStore store = new SessionStore(tempDir);
        Conversation conversation = new Conversation();
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);

        // 创建一个旧会话（修改 meta 使 lastActiveMs 很早）
        manager.start();
        conversation.commit(ConversationTurn.userTextOnly("旧消息"));
        manager.savePending();
        String id = manager.currentId();

        // 手动改写 meta 使 lastActiveMs 在 48 小时前
        long oldTime = System.currentTimeMillis() - 48 * 3600 * 1000L;
        store.writeMeta(id, new SessionMeta(id, "旧标题", "旧摘要", 1, oldTime, oldTime));

        // 清空并恢复
        conversation.replaceAll(List.of());
        SessionLoadResult result = manager.restore(id);

        // 默认 sessionIdleHours=24，所以应插入时间提醒到对话中
        assertEquals(1, result.turns().size(), "loadValidated 只返回历史轮次");
        assertEquals(2, conversation.size(), "对话应含历史 + 时间提醒");
        assertTrue(conversation.snapshot().get(1).userMessage().text().contains("system-reminder"),
                "时间提醒应被 system-reminder 包裹");
    }

    @Test
    void restoreNoTimeReminderWhenRecent() {
        SessionStore store = new SessionStore(tempDir);
        Conversation conversation = new Conversation();
        // 设置 sessionIdleHours=48
        MemoryConfig config = new MemoryConfig(true, 5, 16384, 3, 48, true);
        SessionManager manager = new SessionManager(store, config, null, conversation);

        manager.start();
        conversation.commit(ConversationTurn.userTextOnly("新消息"));
        manager.savePending();
        String id = manager.currentId();

        conversation.replaceAll(List.of());
        SessionLoadResult result = manager.restore(id);

        assertEquals(1, result.turns().size(), "未超时不应插入时间提醒");
    }

    @Test
    void listReturnsAllSessions() {
        SessionStore store = new SessionStore(tempDir);
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, new Conversation());

        manager.start();
        manager.savePending();
        String id1 = manager.currentId();

        manager.beginNew();
        manager.savePending();

        List<SessionMeta> sessions = manager.list();
        assertEquals(2, sessions.size());
    }
}
