package dev.zencode.prompt;

import dev.zencode.chat.Conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestAssemblerRefreshTest {

    @TempDir
    Path tmp;

    private static boolean historyContains(Conversation c, String text) {
        return c.snapshot().stream()
                .anyMatch(t -> t.userMessage() != null && t.userMessage().text().contains(text));
    }

    @Test
    void refreshForNewDirectory后环境快照更新() throws Exception {
        Path dir1 = Files.createDirectory(tmp.resolve("dir1"));
        Path dir2 = Files.createDirectory(tmp.resolve("dir2"));
        RequestAssembler assembler = new RequestAssembler(dir1, "model",
                SystemPromptBuilder.defaults().build(), List.of(), null, null);
        Conversation conversation = new Conversation();

        assembler.assemble(conversation, 1, false, "任务");
        String dir1Abs = dir1.toAbsolutePath().toString();
        String dir2Abs = dir2.toAbsolutePath().toString();
        assertTrue(historyContains(conversation, dir1Abs), "首次 seed 应含旧目录");
        assertFalse(historyContains(conversation, dir2Abs), "首次 seed 不应含新目录");

        // 模拟切换目录
        assembler.refreshForNewDirectory(dir2, null, null);
        assembler.assemble(conversation, 2, false, null);

        assertTrue(historyContains(conversation, dir2Abs), "切换后应追加新目录环境快照");
    }
}
