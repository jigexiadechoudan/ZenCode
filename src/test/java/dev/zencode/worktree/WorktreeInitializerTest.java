package dev.zencode.worktree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorktreeInitializerTest {

    @TempDir
    Path tmp;

    @Test
    void 复制本地配置文件() throws IOException {
        Path repo = Files.createDirectory(tmp.resolve("repo"));
        Path wt = Files.createDirectory(tmp.resolve("wt"));
        Files.writeString(repo.resolve("settings.local.json"), "{\"k\":1}", StandardCharsets.UTF_8);
        WorktreeConfig config = new WorktreeConfig(".zencode/worktrees", List.of(),
                List.of("settings.local.json"), List.of(), 86400, 3600, "wt-");

        WorktreeInitializer.initialize(repo, wt, config);

        assertTrue(Files.exists(wt.resolve("settings.local.json")));
        assertEquals("{\"k\":1}", Files.readString(wt.resolve("settings.local.json")));
    }

    @Test
    void 缺本地配置不报错() throws IOException {
        Path repo = Files.createDirectory(tmp.resolve("repo"));
        Path wt = Files.createDirectory(tmp.resolve("wt"));
        WorktreeConfig config = new WorktreeConfig(".zencode/worktrees", List.of(),
                List.of("missing.json"), List.of(), 86400, 3600, "wt-");
        assertDoesNotThrow(() -> WorktreeInitializer.initialize(repo, wt, config));
    }

    @Test
    void junction源不存在跳过不抛异常() throws IOException {
        Path repo = Files.createDirectory(tmp.resolve("repo"));
        Path wt = Files.createDirectory(tmp.resolve("wt"));
        WorktreeConfig config = new WorktreeConfig(".zencode/worktrees",
                List.of("node_modules"), List.of(), List.of(), 86400, 3600, "wt-");
        assertDoesNotThrow(() -> WorktreeInitializer.initialize(repo, wt, config));
        assertFalse(Files.exists(wt.resolve("node_modules")));
    }

    @Test
    void 复制忽略文件按glob匹配() throws IOException {
        Path repo = Files.createDirectory(tmp.resolve("repo"));
        Path wt = Files.createDirectory(tmp.resolve("wt"));
        Files.writeString(repo.resolve("secret.txt"), "secret", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("keep.txt"), "keep", StandardCharsets.UTF_8);
        WorktreeConfig config = new WorktreeConfig(".zencode/worktrees", List.of(),
                List.of(), List.of("secret.txt"), 86400, 3600, "wt-");

        WorktreeInitializer.initialize(repo, wt, config);

        assertTrue(Files.exists(wt.resolve("secret.txt")));
        assertFalse(Files.exists(wt.resolve("keep.txt")));
    }
}
