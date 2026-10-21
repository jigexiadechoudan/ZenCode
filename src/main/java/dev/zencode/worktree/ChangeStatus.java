package dev.zencode.worktree;

/**
 * 变更检测结果。
 *
 * @param hasUncommitted 是否有未提交修改
 * @param hasUnpushed    是否有未推送 commit
 * @param known          git 检测是否成功；false 表示失败（fail-closed 视为有变更）
 */
public record ChangeStatus(boolean hasUncommitted, boolean hasUnpushed, boolean known) {

    /** 是否有任何变更或检测不确定；用于删除前的保护判定。 */
    public boolean dirty() {
        return !known || hasUncommitted || hasUnpushed;
    }

    /** 干净的确定状态（无任何变更且检测成功）。 */
    public static ChangeStatus clean() {
        return new ChangeStatus(false, false, true);
    }

    /** 检测失败（fail-closed，视为有变更）。 */
    public static ChangeStatus unknown() {
        return new ChangeStatus(false, false, false);
    }
}
