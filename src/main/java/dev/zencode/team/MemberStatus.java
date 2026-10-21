package dev.zencode.team;

/**
 * 队员生命周期状态。
 */
public enum MemberStatus {
    /** 已派生，尚未开始运行。 */
    SPAWNED,
    /** 运行中。 */
    RUNNING,
    /** 自然停止，空闲可续写。 */
    IDLE,
    /** 被终止。 */
    TERMINATED
}
