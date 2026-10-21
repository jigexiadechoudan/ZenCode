package dev.zencode.worktree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * git 子进程封装：worktree 相关命令与变更检测。
 * <p>
 * 复用 EnvironmentProbe 的 ProcessBuilder 直调 git 模式，不引入 JGit。
 * 变更检测采用 fail-closed：git 命令失败时返回 {@link ChangeStatus#unknown()}（视为有变更）。
 * </p>
 */
public final class GitCommand {

    private GitCommand() {}

    /** git 子进程执行结果。 */
    public record GitResult(int exitCode, String stdout, String stderr) {
        public boolean ok() { return exitCode == 0; }
    }

    /** git worktree list 的一行条目。 */
    public record GitWorktreeEntry(Path path, String branch, String head) {}

    /** 在 dir 下执行 git 命令；失败返回非零 exitCode。 */
    public static GitResult run(Path dir, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (dir != null && Files.isDirectory(dir)) {
                pb.directory(dir.toFile());
            }
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            return new GitResult(exit, out, err);
        } catch (IOException e) {
            return new GitResult(-1, "", "git 执行失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitResult(-1, "", "git 执行被中断");
        }
    }

    /** 目录是否为 git 仓库内部。 */
    public static boolean isGitRepository(Path dir) {
        GitResult r = run(dir, "rev-parse", "--is-inside-work-tree");
        return r.ok() && r.stdout().trim().equals("true");
    }

    /** 当前分支名；失败返回空串。 */
    public static String currentBranch(Path dir) {
        GitResult r = run(dir, "branch", "--show-current");
        return r.ok() ? r.stdout().trim() : "";
    }

    /**
     * 变更检测：未提交修改 + 未推送 commit。
     * 任一 git 命令失败 → fail-closed，返回 {@link ChangeStatus#unknown()}。
     */
    public static ChangeStatus detectChanges(Path dir) {
        GitResult status = run(dir, "status", "--porcelain");
        if (!status.ok()) return ChangeStatus.unknown();
        boolean hasUncommitted = !status.stdout().trim().isEmpty();

        GitResult branch = run(dir, "status", "--porcelain", "--branch");
        if (!branch.ok()) return ChangeStatus.unknown();
        boolean hasUnpushed = branch.stdout().contains("[ahead");

        return new ChangeStatus(hasUncommitted, hasUnpushed, true);
    }

    /** 列出所有 worktree（porcelain 解析）。 */
    public static List<GitWorktreeEntry> worktreeList(Path root) {
        GitResult r = run(root, "worktree", "list", "--porcelain");
        if (!r.ok()) return List.of();
        List<GitWorktreeEntry> result = new ArrayList<>();
        String path = null, head = null, branch = null;
        for (String line : r.stdout().split("\n")) {
            if (line.startsWith("worktree ")) {
                if (path != null) {
                    result.add(new GitWorktreeEntry(Path.of(path), branch, head));
                }
                path = line.substring("worktree ".length()).trim();
                head = null;
                branch = null;
            } else if (line.startsWith("HEAD ")) {
                head = line.substring("HEAD ".length()).trim();
            } else if (line.startsWith("branch ")) {
                branch = line.substring("branch ".length()).trim();
                if (branch.startsWith("refs/heads/")) {
                    branch = branch.substring("refs/heads/".length());
                }
            }
        }
        if (path != null) {
            result.add(new GitWorktreeEntry(Path.of(path), branch, head));
        }
        return result;
    }

    /** 创建 worktree（新建分支）。 */
    public static GitResult worktreeAdd(Path root, String path, String branch) {
        return run(root, "worktree", "add", path, "-b", branch);
    }

    /** 删除 worktree。 */
    public static GitResult worktreeRemove(Path root, String path, boolean force) {
        if (force) {
            return run(root, "worktree", "remove", "--force", path);
        }
        return run(root, "worktree", "remove", path);
    }
}
