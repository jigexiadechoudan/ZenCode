package dev.zencode.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionJournalTest {
    @TempDir Path tempDir;

    private ToolCall writeCall() {
        return new ToolCall("call-1", "WriteFile",
                Map.of("path", "a.txt", "content", "hello"), "", "openai");
    }

    @Test
    void unfinishedSideEffectBecomesUnknownAndCannotBeReplayed() throws Exception {
        ToolExecutionJournal journal = new ToolExecutionJournal(tempDir, () -> "session-1");
        var created = journal.begin(writeCall(), ToolCategory.WRITE);
        journal.transition(created, ToolExecutionJournal.State.STARTED);
        // 模拟原 JVM 已退出：同一执行的最后一条记录归属不存在的进程。
        Path file = tempDir.resolve(".zencode/tool-executions.jsonl");
        var deadOwner = new ToolExecutionJournal.Entry(created.executionId(), created.sessionId(),
                created.callId(), created.tool(), created.argumentsHash(), created.category(),
                ToolExecutionJournal.State.STARTED, System.currentTimeMillis(), "0:0");
        Files.writeString(file, new ObjectMapper().writeValueAsString(deadOwner) + "\n",
                StandardOpenOption.APPEND);

        var unresolved = journal.unresolvedForSession("session-1");
        assertEquals(1, unresolved.size());
        assertEquals(ToolExecutionJournal.State.UNKNOWN, unresolved.get(0).state());
        assertThrows(java.io.IOException.class, () -> journal.begin(writeCall(), ToolCategory.WRITE));

        assertTrue(journal.resolveUnknown(created.executionId(), ToolExecutionJournal.State.FAILED));
        assertNotNull(journal.begin(writeCall(), ToolCategory.WRITE));
    }

    @Test
    void failedBeforeStartIsRetryable() throws Exception {
        ToolExecutionJournal journal = new ToolExecutionJournal(tempDir);
        var created = journal.begin(writeCall(), ToolCategory.WRITE);
        journal.transition(created, ToolExecutionJournal.State.FAILED);
        assertTrue(journal.unresolved().isEmpty());
        assertNotNull(journal.begin(writeCall(), ToolCategory.WRITE));
    }
}
