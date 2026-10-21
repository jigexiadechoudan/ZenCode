package dev.zencode.worktree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorktreeManagerTest {

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

    private static WorktreeManager manager(Path repo) {
        return new WorktreeManager(repo, WorktreeConfig.defaults(),
                new WorktreeSessionStore(repo), () -> {});
    }

    @Test
    void create创建worktree并git可见() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        WorktreeManager mgr = manager(repo);
        WorktreeOperationResult r = mgr.create("feature-a");
        assertTrue(r.success(), r.message());
        assertTrue(Files.isDirectory(repo.resolve(".zencode/worktrees/feature-a")));
        var entries = GitCommand.worktreeList(repo);
        assertTrue(entries.stream().anyMatch(e -> e.path().toString().contains("feature-a")));
        assertEquals("feature-a", mgr.currentName());
        assertEquals(repo.resolve(".zencode/worktrees/feature-a"), mgr.currentDirectory());
    }

    @Test
    void list包含已创建的worktree() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        WorktreeManager mgr = manager(repo);
        mgr.create("feature-a");
        assertEquals(1, mgr.list().size());
        assertEquals("feature-a", mgr.list().get(0).name());
    }

    @Test
    void enter与exit切换目录() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        WorktreeManager mgr = manager(repo);
        mgr.create("feature-d");
        Path wt = mgr.currentDirectory();
        mgr.exit();
        assertEquals(repo, mgr.currentDirectory());
        assertNull(mgr.currentName());
        mgr.enter("feature-d");
        assertEquals(wt, mgr.currentDirectory());
        assertEquals("feature-d", mgr.currentName());
    }

    @Test
    void 有未提交修改remove被拒绝() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        WorktreeManager mgr = manager(repo);
        mgr.create("feature-b");
        Path wt = mgr.currentDirectory();
        Files.writeString(wt.resolve("a.txt"), "changed\n", StandardCharsets.UTF_8);

        WorktreeOperationResult r = mgr.remove("feature-b", false);
        assertFalse(r.success());
        assertTrue(r.message().contains("拒绝"), r.message());
        assertTrue(Files.isDirectory(wt), "未确认丢弃时目录应保留");

        WorktreeOperationResult r2 = mgr.remove("feature-b", true);
        assertTrue(r2.success(), r2.message());
        assertFalse(Files.isDirectory(wt), "确认丢弃后目录应删除");
    }

    @Test
    void 快速恢复已存在目录() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        WorktreeManager mgr = manager(repo);
        mgr.create("feature-c");
        mgr.exit();
        WorktreeOperationResult r = mgr.create("feature-c");
        assertTrue(r.success());
        assertTrue(r.message().contains("恢复"), r.message());
    }

    @Test
    void createIsolated用tempPrefix与isolation标记() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        WorktreeManager mgr = manager(repo);
        WorktreeOperationResult r = mgr.createIsolated("explore-x");
        assertTrue(r.success(), r.message());
        assertNull(mgr.currentName(), "隔离子任务不能切换主会话目录");
        assertEquals(repo, mgr.currentDirectory());
        assertEquals("wt-explore-x", r.info().name());
        assertTrue(mgr.list().stream()
                .anyMatch(i -> i.isolation() && "subagent".equals(i.source())));
    }

    @Test
    void 路径遍历名被拒绝() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        WorktreeManager mgr = manager(repo);
        assertFalse(mgr.create("../etc").success());
        assertFalse(mgr.create("C:\\x").success());
        assertFalse(mgr.create("a:b").success());
        // 未在受管根目录外创建
        assertFalse(Files.exists(repo.resolve("etc")));
    }

    @Test
    void autoCleanup无变更自动删() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        WorktreeManager mgr = manager(repo);
        WorktreeOperationResult created = mgr.createIsolated("clean-x");
        String name = created.info().name();
        Path wt = created.info().path();
        WorktreeOperationResult r = mgr.autoCleanup(name);
        assertTrue(r.success());
        assertFalse(Files.isDirectory(wt), "无变更应自动删除");
    }
}
