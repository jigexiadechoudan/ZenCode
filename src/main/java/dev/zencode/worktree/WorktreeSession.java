package dev.zencode.worktree;

import java.nio.file.Path;
import java.util.List;

/**
 * 持久化到磁盘的 worktree 会话状态。
 *
 * @param currentName 当前 worktree 名；null=主仓库
 * @param currentPath 当前工作目录
 * @param worktrees   已知 worktree 列表（恢复用）
 */
public record WorktreeSession(
        String currentName,
        Path currentPath,
        List<WorktreeInfo> worktrees) {

    public WorktreeSession {
        currentPath = currentPath == null ? null : currentPath;
        worktrees = worktrees == null ? List.of() : List.copyOf(worktrees);
    }
}
