package dev.zencode.command.builtin;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandResult;
import dev.zencode.worktree.GitCommand;
import dev.zencode.worktree.WorktreeConfig;
import dev.zencode.worktree.WorktreeManager;
import dev.zencode.worktree.WorktreeSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorktreeCommandTest {

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

    private static String show(CommandResult result) {
        assertInstanceOf(CommandResult.ShowMessage.class, result);
        return ((CommandResult.ShowMessage) result).text();
    }

    private WorktreeCommand command(WorktreeManager mgr) {
        return new WorktreeCommand(mgr);
    }

    private WorktreeManager manager(Path repo) {
        return new WorktreeManager(repo, WorktreeConfig.defaults(),
                new WorktreeSessionStore(repo), () -> {});
    }

    @Test
    void list空时提示无worktree() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        String text = show(command(manager(repo)).handle(new CommandContext(null, "worktree", "list")));
        assertTrue(text.contains("无 worktree"), text);
    }

    @Test
    void create后list可见() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        WorktreeManager mgr = manager(repo);
        WorktreeCommand cmd = command(mgr);
        String created = show(cmd.handle(new CommandContext(null, "worktree", "create feature-x")));
        assertTrue(created.contains("feature-x"), created);
        String listed = show(cmd.handle(new CommandContext(null, "worktree", "list")));
        assertTrue(listed.contains("feature-x"), listed);
    }

    @Test
    void enter与exit切换() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        WorktreeManager mgr = manager(repo);
        WorktreeCommand cmd = command(mgr);
        cmd.handle(new CommandContext(null, "worktree", "create feature-y"));
        assertEquals("feature-y", mgr.currentName());
        show(cmd.handle(new CommandContext(null, "worktree", "exit")));
        assertNull(mgr.currentName());
        String entered = show(cmd.handle(new CommandContext(null, "worktree", "enter feature-y")));
        assertTrue(entered.contains("feature-y"), entered);
        assertEquals("feature-y", mgr.currentName());
    }

    @Test
    void remove有变更被拒绝() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        WorktreeManager mgr = manager(repo);
        WorktreeCommand cmd = command(mgr);
        cmd.handle(new CommandContext(null, "worktree", "create feature-z"));
        Files.writeString(mgr.currentDirectory().resolve("a.txt"), "changed\n", StandardCharsets.UTF_8);
        String removed = show(cmd.handle(new CommandContext(null, "worktree", "remove feature-z")));
        assertTrue(removed.contains("拒绝"), removed);
        // --discard 删除
        String discarded = show(cmd.handle(new CommandContext(null, "worktree", "remove feature-z --discard")));
        assertTrue(discarded.contains("已删除"), discarded);
    }

    @Test
    void 无参数提示用法() throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        String text = show(command(manager(repo)).handle(new CommandContext(null, "worktree", "")));
        assertTrue(text.contains("用法"), text);
    }
}
