package dev.zencode.worktree;

/**
 * worktree 操作统一返回结果。
 *
 * @param success 是否成功
 * @param message 可读消息（成功提示或失败原因）
 * @param info    关联的 worktree 信息（可为 null）
 */
public record WorktreeOperationResult(boolean success, String message, WorktreeInfo info) {

    public WorktreeOperationResult {
        message = message == null ? "" : message;
    }

    public static WorktreeOperationResult ok(String message, WorktreeInfo info) {
        return new WorktreeOperationResult(true, message, info);
    }

    public static WorktreeOperationResult fail(String message) {
        return new WorktreeOperationResult(false, message, null);
    }
}
