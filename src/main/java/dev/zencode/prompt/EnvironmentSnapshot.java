package dev.zencode.prompt;

/**
 * 会话级环境上下文不可变快照（F3）。
 * <p>
 * 每个会话在第一次用户提交时探测一次并保存，之后通过已确认历史长期携带，
 * 不再重新探测或追加。工作目录、分支、Shell 等属于外部数据，注入 reminder 前必须转义。
 * Git 探测失败只使对应字段降级，不得阻止会话启动（N6）。
 * </p>
 */
public record EnvironmentSnapshot(
        String workingDirectory,
        String windowsVersion,
        String cpuArchitecture,
        String shell,
        String currentDate,
        String modelName,
        boolean inGitTree,
        String gitBranch,
        boolean hasUncommittedChanges) {

    public EnvironmentSnapshot {
        gitBranch = gitBranch == null ? "未知" : gitBranch;
    }
}