package dev.zencode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void filePaths() {
        MemoryStore store = new MemoryStore(tempDir, tempDir, MemoryConfig.defaults());
        assertEquals(tempDir.resolve(".zencode/memories.md"), store.projectFile());
        assertEquals(tempDir.resolve(".zencode/memories.md"), store.userFile());
    }

    @Test
    void writeAndReadProject() {
        MemoryStore store = new MemoryStore(tempDir, tempDir, MemoryConfig.defaults());
        assertTrue(store.write(MemoryStore.Scope.PROJECT, "项目知识：ZenCode 使用 Java"));
        assertEquals("项目知识：ZenCode 使用 Java", store.read(MemoryStore.Scope.PROJECT));
    }

    @Test
    void writeAndReadUser() {
        // 用不同目录确保 userFile 与 projectFile 不同
        Path userDir = tempDir.resolve("userhome");
        MemoryStore store = new MemoryStore(tempDir, userDir, MemoryConfig.defaults());
        assertTrue(store.write(MemoryStore.Scope.USER, "用户偏好：浅色主题"));
        assertEquals("用户偏好：浅色主题", store.read(MemoryStore.Scope.USER));
    }

    @Test
    void readMissingReturnsEmpty() {
        MemoryStore store = new MemoryStore(tempDir, tempDir, MemoryConfig.defaults());
        assertTrue(store.read(MemoryStore.Scope.PROJECT).isEmpty());
        assertTrue(store.read(MemoryStore.Scope.USER).isEmpty());
    }

    @Test
    void readMergedProjectBeforeUser() throws Exception {
        // 写入项目级
        Files.createDirectories(tempDir.resolve(".zencode"));
        Files.writeString(tempDir.resolve(".zencode/memories.md"), "项目记忆");

        // 写入用户级（不同目录）
        Path userDir = tempDir.resolve("userhome");
        Files.createDirectories(userDir.resolve(".zencode"));
        Files.writeString(userDir.resolve(".zencode/memories.md"), "用户记忆");

        MemoryStore store = new MemoryStore(tempDir, userDir, MemoryConfig.defaults());
        String merged = store.readMerged();
        assertTrue(merged.contains("项目记忆"), "应包含项目记忆");
        assertTrue(merged.contains("用户记忆"), "应包含用户记忆");
        assertTrue(merged.indexOf("项目记忆") < merged.indexOf("用户记忆"), "项目记忆应在用户记忆之前");
    }

    @Test
    void readMergedSizeTruncation() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("A");
        }
        Files.createDirectories(tempDir.resolve(".zencode"));
        Files.writeString(tempDir.resolve(".zencode/memories.md"), sb.toString());

        MemoryConfig config = new MemoryConfig(true, 5, 50, 3, 24, true);
        MemoryStore store = new MemoryStore(tempDir, tempDir, config);
        String merged = store.readMerged();
        assertTrue(merged.length() <= 50, "合并结果应被截断到 50 字符");
    }

    @Test
    void writeCreatesDirectory() {
        MemoryStore store = new MemoryStore(tempDir, tempDir, MemoryConfig.defaults());
        assertTrue(store.write(MemoryStore.Scope.PROJECT, "记忆内容"));
        assertTrue(Files.exists(tempDir.resolve(".zencode")));
        assertTrue(Files.exists(tempDir.resolve(".zencode/memories.md")));
    }
}
