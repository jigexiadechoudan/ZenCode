package dev.zencode.team;

/**
 * 队员执行后端抽象：spawn 启动、wake 唤醒、stop 停止、status 查询。
 * <p>
 * in-process 后端在同进程虚拟线程运行队员；tmux/iTerm2 后端构造命令并管理
 * 外部 CLI 进程（独立窗格强隔离）。{@link #available} 做环境检测，
 * {@link #describe} 返回可读检测结果（不静默降级的可观测性）。
 * </p>
 */
public interface MemberBackend {

    /** 后端类型。 */
    BackendType type();

    /** 环境检测：当前环境是否可用。 */
    boolean available();

    /** 检测结果的可读说明（记录到队员元信息与日志）。 */
    String describe();

    /** 启动队员；返回执行句柄。 */
    MemberHandle spawn(TeamRuntime rt, TeamMember member, String task);

    /** 唤醒目标（独立进程后端发送唤醒按键；in-process 为 no-op）。 */
    void wake(TeamRuntime rt, TeamMember member);

    /** 停止队员（取消 run / 杀进程 / 关窗格）。 */
    void stop(TeamRuntime rt, TeamMember member);
}
