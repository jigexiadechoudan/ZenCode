package dev.zencode.team;

import java.io.IOException;
import java.util.List;

/**
 * tmux 窗格后端：构造 tmux 命令并在独立 pane 启动完整 CLI 实例（强隔离）。
 * <p>
 * 命令构造为纯函数（可单测）；真实窗格端到端在 Windows 受阻（AGENTS.md），
 * 不冒充验收。spawn/wake/stop 用 ProcessBuilder 执行，失败包装为可读错误。
 * 文本参数做引号转义，防命令注入。窗格目标为 {@code mew-<队员名>}。
 * </p>
 */
public final class TmuxBackend implements MemberBackend {

    private final String cliCommand;

    public TmuxBackend() {
        this("zencode");
    }

    /** @param cliCommand 外部 CLI 实例的可执行名/路径（默认 "zencode"） */
    public TmuxBackend(String cliCommand) {
        this.cliCommand = cliCommand;
    }

    @Override
    public BackendType type() {
        return BackendType.TMUX;
    }

    @Override
    public boolean available() {
        return BackendDetector.tmuxAvailable();
    }

    @Override
    public String describe() {
        return "tmux 窗格后端: " + (available() ? "可用" : "未检测到 tmux 可执行文件");
    }

    /** 构造 spawn 命令字符串（纯函数，测试用）。 */
    public String buildSpawnCommand(String teamName, TeamMember member, String workDir, String task) {
        return "tmux new-window -n " + quote(member.name())
                + " -c " + quote(workDir)
                + " " + cliCommand
                + " --team " + quote(teamName)
                + " --member " + quote(member.agentId())
                + " --spawn-task " + quote(task);
    }

    /** 构造唤醒命令字符串：向目标窗格发送回车，触发重轮询。 */
    public String buildWakeCommand(String target) {
        return "tmux send-keys -t " + quote(target) + " Enter";
    }

    /** 构造停止命令字符串：关闭目标窗格。 */
    public String buildStopCommand(String target) {
        return "tmux kill-window -t " + quote(target);
    }

    @Override
    public MemberHandle spawn(TeamRuntime rt, TeamMember member, String task) {
        if (!available()) {
            throw new TeamException("后端 TMUX 不可用: " + describe());
        }
        String target = target(member);
        String shellCommand = cliCommand
                + " --team " + quote(rt.team().name())
                + " --member " + quote(member.agentId())
                + " --spawn-task " + quote(task);
        execute(List.of("tmux", "new-window", "-n", member.name(),
                "-c", member.workDir(), shellCommand));
        return new MemberHandle("tmux", target, "tmux 窗格已创建: " + shellCommand);
    }

    @Override
    public void wake(TeamRuntime rt, TeamMember member) {
        if (!available()) {
            throw new TeamException("后端 TMUX 不可用: " + describe());
        }
        execute(List.of("tmux", "send-keys", "-t", target(member), "Enter"));
    }

    @Override
    public void stop(TeamRuntime rt, TeamMember member) {
        execute(List.of("tmux", "kill-window", "-t", target(member)));
    }

    private static String target(TeamMember member) {
        return "mew-" + member.name();
    }

    private static void execute(List<String> args) {
        try {
            new ProcessBuilder(args).inheritIO().start();
        } catch (IOException e) {
            throw new TeamException("tmux 命令执行失败: " + e.getMessage());
        }
    }

    /** 双引号包裹并转义内部引号，防命令注入。 */
    static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\\\"")) + "\"";
    }
}
