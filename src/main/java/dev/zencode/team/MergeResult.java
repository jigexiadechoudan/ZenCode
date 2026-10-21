package dev.zencode.team;

import java.util.List;

/**
 * 收敛合并结果。
 *
 * @param success        全部目标分支是否合并成功
 * @param message        汇总说明
 * @param mergedBranches 成功合并的分支名列表
 * @param conflicts      出现冲突的分支名列表
 * @param rolledBack     已回滚的分支名列表
 */
public record MergeResult(
        boolean success,
        String message,
        List<String> mergedBranches,
        List<String> conflicts,
        List<String> rolledBack) {

    public MergeResult {
        mergedBranches = mergedBranches == null ? List.of() : List.copyOf(mergedBranches);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        rolledBack = rolledBack == null ? List.of() : List.copyOf(rolledBack);
    }

    public static MergeResult ok(String message, List<String> merged, List<String> conflicts, List<String> rolledBack) {
        return new MergeResult(true, message, merged, conflicts, rolledBack);
    }

    public static MergeResult fail(String message) {
        return new MergeResult(false, message, List.of(), List.of(), List.of());
    }
}
