package dev.zencode.worktree;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 一个 worktree 的只读描述。
 *
 * @param name       slug 名（已平铺）
 * @param path       worktree 绝对路径
 * @param branch     分支名
 * @param head       HEAD 提交（短哈希，可空）
 * @param createdAt  创建时间
 * @param isolation  是否隔离子 Agent 创建
 * @param source     来源："manual" | "subagent"
 */
public record WorktreeInfo(
        String name,
        Path path,
        String branch,
        String head,
        Instant createdAt,
        boolean isolation,
        String source) {

    public WorktreeInfo {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("worktree 名不能为空");
        if (path == null) throw new IllegalArgumentException("worktree 路径不能为空");
        branch = branch == null ? "" : branch;
        head = head == null ? "" : head;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        source = source == null ? "manual" : source;
    }
}
