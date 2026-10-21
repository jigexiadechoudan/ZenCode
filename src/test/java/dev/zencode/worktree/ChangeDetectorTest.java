package dev.zencode.worktree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChangeDetectorTest {

    @TempDir
    Path tmp;

    private static Path initRepo(Path dir) throws IOException {
        Files.createDirectories(dir);
        GitCommand.run(dir, "init");
        Files.writeString(dir.resolve("a.txt"), "hello\n", StandardCharsets.UTF_8);
        GitCommand.run(dir, "add", "a.txt");
        GitCommand.run(dir, "-c", "user.email=test@test.com",
                "-c", "user.name=test", "commit", "-m", "init");
        return dir;
    }

    @Test
    void 干净仓库无变更() throws IOException {
        Path repo = initRepo(tmp.resolve("repo"));
        ChangeStatus status = GitCommand.detectChanges(repo);
        assertTrue(status.known());
        assertFalse(status.hasUncommitted());
        assertFalse(status.dirty());
    }

    @Test
    void 有未提交修改标记dirty() throws IOException {
        Path repo = initRepo(tmp.resolve("repo"));
        Files.writeString(repo.resolve("a.txt"), "changed\n", StandardCharsets.UTF_8);
        ChangeStatus status = GitCommand.detectChanges(repo);
        assertTrue(status.known());
        assertTrue(status.hasUncommitted());
        assertTrue(status.dirty());
    }

    @Test
    void 非git目录failClosed() throws IOException {
        Path nonGit = Files.createDirectory(tmp.resolve("non-git"));
        ChangeStatus status = GitCommand.detectChanges(nonGit);
        assertFalse(status.known());
        assertTrue(status.dirty(), "检测失败应视为有变更（fail-closed）");
    }

    @Test
    void isGitRepository识别仓库与非仓库() throws IOException {
        Path repo = initRepo(tmp.resolve("repo"));
        assertTrue(GitCommand.isGitRepository(repo));
        Path nonGit = Files.createDirectory(tmp.resolve("non-git"));
        assertFalse(GitCommand.isGitRepository(nonGit));
    }

    @Test
    void worktreeList包含主仓库() throws IOException {
        Path repo = initRepo(tmp.resolve("repo"));
        var entries = GitCommand.worktreeList(repo);
        assertFalse(entries.isEmpty(), "git worktree list 应至少包含主仓库");
    }
}
