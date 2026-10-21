package dev.zencode.team;

/**
 * 队员执行后端类型。AUTO 表示按环境自动检测（检测结果落地为具体类型）。
 */
public enum BackendType {
    TMUX,
    ITERM2,
    INPROCESS,
    AUTO
}
