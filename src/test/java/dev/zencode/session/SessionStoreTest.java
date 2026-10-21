package dev.zencode.session;

import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolResultContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void writeAndLoadTurns() {
        SessionStore store = new SessionStore(tempDir);
        String id = store.newSessionId();
        assertNotNull(id);

        SessionTurnDto t1 = SessionTurnDto.fromTurn(ConversationTurn.userTextOnly("你好"));
        SessionTurnDto t2 = SessionTurnDto.fromTurn(ConversationTurn.userTextOnly("再见"));

        store.appendTurn(id, t1);
        store.appendTurn(id, t2);

        List<SessionTurnDto> loaded = store.load(id);
        assertEquals(2, loaded.size());
        assertEquals("你好", loaded.get(0).contents().get(0).text);
        assertEquals("再见", loaded.get(1).contents().get(0).text);
    }

    @Test
    void writeAndReadMeta() {
        SessionStore store = new SessionStore(tempDir);
        String id = store.newSessionId();

        SessionMeta meta = new SessionMeta(id, "测试会话", "摘要", 5, 1000L, 2000L);
        store.writeMeta(id, meta);

        SessionMeta read = store.readMeta(id);
        assertNotNull(read);
        assertEquals(id, read.id());
        assertEquals("测试会话", read.title());
        assertEquals(5, read.messageCount());
        assertEquals(1000L, read.createdAtMs());
        assertEquals(2000L, read.lastActiveMs());
    }

    @Test
    void listReturnsAllMeta() {
        SessionStore store = new SessionStore(tempDir);
        String id1 = store.newSessionId();
        String id2 = store.newSessionId();

        store.writeMeta(id1, new SessionMeta(id1, "会话1", "", 1, 100L, 300L));
        store.writeMeta(id2, new SessionMeta(id2, "会话2", "", 2, 200L, 400L));

        List<SessionMeta> list = store.list();
        assertEquals(2, list.size());
        // 按 lastActiveMs 降序
        assertEquals(id2, list.get(0).id());
        assertEquals(id1, list.get(1).id());
    }

    @Test
    void skipBadLinesOnLoad() {
        SessionStore store = new SessionStore(tempDir);
        String id = store.newSessionId();

        // 写入正常行
        SessionTurnDto good = SessionTurnDto.fromTurn(ConversationTurn.userTextOnly("正常"));
        store.appendTurn(id, good);

        // 手动写入坏行
        Path dir = store.sessionsDir();
        assertNotNull(dir);
        try {
            java.nio.file.Files.writeString(dir.resolve(id + ".jsonl"),
                    "这不是合法 JSON\n", java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            fail("写入坏行失败");
        }

        // 再写入正常行
        SessionTurnDto good2 = SessionTurnDto.fromTurn(ConversationTurn.userTextOnly("也正常"));
        store.appendTurn(id, good2);

        List<SessionTurnDto> loaded = store.load(id);
        assertEquals(2, loaded.size());
        assertEquals("正常", loaded.get(0).contents().get(0).text);
        assertEquals("也正常", loaded.get(1).contents().get(0).text);
    }

    @Test
    void deleteRemovesFiles() {
        SessionStore store = new SessionStore(tempDir);
        String id = store.newSessionId();

        store.appendTurn(id, SessionTurnDto.fromTurn(ConversationTurn.userTextOnly("测试")));
        store.writeMeta(id, new SessionMeta(id, "测试", "", 1, 1L, 1L));

        assertTrue(store.delete(id));
        assertTrue(store.load(id).isEmpty());
        assertNull(store.readMeta(id));
    }

    @Test
    void sessionsDirCreated() {
        SessionStore store = new SessionStore(tempDir);
        Path dir = store.sessionsDir();
        assertNotNull(dir);
        assertTrue(java.nio.file.Files.exists(dir));
        assertTrue(dir.getFileName().toString().equals("sessions"));
        assertTrue(dir.getParent().getFileName().toString().equals(".zencode"));
    }

    @Test
    void loadNonExistentReturnsEmpty() {
        SessionStore store = new SessionStore(tempDir);
        List<SessionTurnDto> loaded = store.load("nonexistent");
        assertTrue(loaded.isEmpty());
    }

    // ---- loadValidated 测试 ----

    @Test
    void loadValidatedCompleteTurns() {
        SessionStore store = new SessionStore(tempDir);
        String id = store.newSessionId();

        store.appendTurn(id, SessionTurnDto.fromTurn(ConversationTurn.userTextOnly("第一轮")));
        store.appendTurn(id, SessionTurnDto.fromTurn(ConversationTurn.userTextOnly("第二轮")));

        SessionLoadResult result = store.loadValidated(id);
        assertEquals(2, result.turns().size());
        assertNull(result.trimmedCallId());
        assertEquals(0, result.skippedLines());
    }

    @Test
    void loadValidatedTrimsUnpairedToolUse() {
        SessionStore store = new SessionStore(tempDir);
        String id = store.newSessionId();

        // 写入两轮完整轮次
        store.appendTurn(id, SessionTurnDto.fromTurn(ConversationTurn.userTextOnly("第一轮")));

        // 手动构造有 tool_use 无 tool_result 的 DTO
        SessionTurnDto.ContentBlock block = new SessionTurnDto.ContentBlock();
        block.t = "tool_call";
        block.callId = "call-unpaired";
        block.toolName = "read_file";
        block.arguments = Map.of("path", "/test.txt");
        block.rawArguments = "{\"path\":\"/test.txt\"}";
        block.source = "anthropic";

        SessionTurnDto brokenDto = new SessionTurnDto(1, List.of(block), null, System.currentTimeMillis());
        String brokenJson = brokenDto.toJson();

        // 手动追加到 JSONL
        try {
            java.nio.file.Files.writeString(
                    store.sessionsDir().resolve(id + ".jsonl"),
                    brokenJson + "\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            fail("写入未配对 tool_use 失败");
        }

        // 再写一轮正常的
        store.appendTurn(id, SessionTurnDto.fromTurn(ConversationTurn.userTextOnly("第三轮")));

        SessionLoadResult result = store.loadValidated(id);
        // 应该截断到第一轮（去掉含未配对 tool_use 的行及之后）
        assertEquals(1, result.turns().size());
        assertEquals("call-unpaired", result.trimmedCallId());
        assertEquals(0, result.skippedLines());
    }

    @Test
    void loadValidatedPairedToolUseNotTrimmed() {
        SessionStore store = new SessionStore(tempDir);
        String id = store.newSessionId();

        // 构造完整轮次：含 tool_use + tool_result 配对
        ConversationTurn turn = new ConversationTurn(List.of(
                new ConversationContent.UserText("查询"),
                new ConversationContent.ToolCallBlock(
                        new ToolCall("call-1", "read_file", Map.of("path", "/f.txt"),
                                "{\"path\":\"/f.txt\"}", "anthropic")),
                new ConversationContent.ToolResultBlock(
                        new ToolResultContent(
                                ToolResult.success("call-1", "read_file", "文件内容"),
                                "call-1", "read_file"))
        ), null, null);

        store.appendTurn(id, SessionTurnDto.fromTurn(turn));
        store.appendTurn(id, SessionTurnDto.fromTurn(ConversationTurn.userTextOnly("后续")));

        SessionLoadResult result = store.loadValidated(id);
        assertEquals(2, result.turns().size());
        assertNull(result.trimmedCallId());
    }

    @Test
    void loadValidatedCountsSkippedLines() {
        SessionStore store = new SessionStore(tempDir);
        String id = store.newSessionId();

        store.appendTurn(id, SessionTurnDto.fromTurn(ConversationTurn.userTextOnly("正常行")));

        // 手动写入坏行
        try {
            java.nio.file.Files.writeString(
                    store.sessionsDir().resolve(id + ".jsonl"),
                    "坏行1\n坏行2\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            fail("写入坏行失败");
        }

        store.appendTurn(id, SessionTurnDto.fromTurn(ConversationTurn.userTextOnly("也正常")));

        SessionLoadResult result = store.loadValidated(id);
        assertEquals(2, result.turns().size());
        assertEquals(2, result.skippedLines());
        assertNull(result.trimmedCallId());
    }
}
