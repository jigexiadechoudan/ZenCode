package dev.zencode.worktree;

import java.util.List;

/**
 * worktree 系统配置。
 *
 * @param worktreesRoot        相对主仓库根目录，默认 ".zencode/worktrees"
 * @param linkDirs             依赖目录（相对主仓库），创建 junction
 * @param copyLocalFiles       本地配置文件名（如 settings.local.json）
 * @param copyIgnored          被 gitignore 但需复制的 glob
 * @param staleTimeoutSeconds  过期阈值（秒），默认 86400
 * @param cleanupIntervalSeconds 清理周期（秒），默认 3600
 * @param tempPrefix           临时目录前缀，默认 "wt-"
 */
public record WorktreeConfig(
        String worktreesRoot,
        List<String> linkDirs,
        List<String> copyLocalFiles,
        List<String> copyIgnored,
        long staleTimeoutSeconds,
        long cleanupIntervalSeconds,
        String tempPrefix) {

    public WorktreeConfig {
        worktreesRoot = worktreesRoot == null || worktreesRoot.isBlank() ? ".zencode/worktrees" : worktreesRoot;
        linkDirs = linkDirs == null ? List.of() : List.copyOf(linkDirs);
        copyLocalFiles = copyLocalFiles == null ? List.of() : List.copyOf(copyLocalFiles);
        copyIgnored = copyIgnored == null ? List.of() : List.copyOf(copyIgnored);
        if (staleTimeoutSeconds <= 0) staleTimeoutSeconds = 86400;
        if (cleanupIntervalSeconds <= 0) cleanupIntervalSeconds = 3600;
        tempPrefix = tempPrefix == null || tempPrefix.isBlank() ? "wt-" : tempPrefix;
    }

    /** 默认配置。 */
    public static WorktreeConfig defaults() {
        return new WorktreeConfig(".zencode/worktrees", List.of(),
                List.of("settings.local.json"), List.of(), 86400, 3600, "wt-");
    }
}
