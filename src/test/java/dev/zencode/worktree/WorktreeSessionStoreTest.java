package dev.zencode.worktree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorktreeSessionStoreTest {

    @TempDir
    Path tmp;

    @Test
    void save后load往返一致() throws Exception {
        WorktreeSessionStore store = new WorktreeSessionStore(tmp);
        WorktreeInfo info = new WorktreeInfo("feature-a", tmp.resolve("wt-feature-a"),
                "feature-a", "abc123", Instant.ofEpochMilli(1000), false, "manual");
        WorktreeSession session = new WorktreeSession("feature-a",
                tmp.resolve("wt-feature-a"), List.of(info));

        store.save(session);
        WorktreeSession loaded = store.load().orElseThrow();

        assertEquals("feature-a", loaded.currentName());
        assertEquals(session.currentPath(), loaded.currentPath());
        assertEquals(1, loaded.worktrees().size());
        assertEquals("feature-a", loaded.worktrees().get(0).name());
        assertEquals(1000L, loaded.worktrees().get(0).createdAt().toEpochMilli());
        assertFalse(loaded.worktrees().get(0).isolation());
    }

    @Test
    void 文件不存在返回空() {
        WorktreeSessionStore store = new WorktreeSessionStore(tmp);
        assertTrue(store.load().isEmpty());
    }

    @Test
    void 坏JSON返回空() throws Exception {
        WorktreeSessionStore store = new WorktreeSessionStore(tmp);
        Files.createDirectories(store.file().getParent());
        Files.writeString(store.file(), "not-json{{{", StandardCharsets.UTF_8);
        assertTrue(store.load().isEmpty());
    }
}
