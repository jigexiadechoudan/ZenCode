package dev.zencode.team;

/**
 * 队员执行句柄：外部进程后端的 pid/窗格目标；in-process 后端为线程描述。
 *
 * @param pid    进程 ID（外部进程后端）；in-process 为 "thread"
 * @param target 唤醒/停止目标（tmux pane 标识等）；无则空
 * @param note   可读说明（检测/启动结果）
 */
public record MemberHandle(String pid, String target, String note) {
}
