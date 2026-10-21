package dev.zencode.team;

/**
 * 团队系统异常：可读错误信息，spawn/协调/合并等失败路径使用。
 */
public final class TeamException extends RuntimeException {
    public TeamException(String message) {
        super(message);
    }
}
