package dev.zencode.team;

import java.io.IOException;
import java.util.List;

/**
 * iTerm2 pane 后端（仅 macOS）：用 osascript 创建独立 pane 运行完整 CLI 实例。
 * <p>
 * 命令构造为纯函数（可单测）；真实 pane 端到端在 Windows 受阻，不冒充验收。
 * spawn/wake/stop 用 ProcessBuilder 执行 osascript，失败包装为可读错误。
 * </p>
 */
public final class Iterm2Backend implements MemberBackend {

    private final String cliCommand;

    public Iterm2Backend() {
        this("zencode");
    }

    /** @param cliCommand 外部 CLI 实例的可执行名/路径（默认 "zencode"） */
    public Iterm2Backend(String cliCommand) {
        this.cliCommand = cliCommand;
    }

    @Override
    public BackendType type() {
        return BackendType.ITERM2;
    }

    @Override
    public boolean available() {
        return BackendDetector.iterm2Available();
    }

    @Override
    public String describe() {
        return "iTerm2 pane 后端（仅 macOS）: " + (available() ? "可用" : "不可用（需 macOS 且安装 iTerm.app）");
    }

    /** 构造 spawn 命令字符串（纯函数，测试用）。 */
    public String buildSpawnCommand(String teamName, TeamMember member, String task) {
        String shellCommand = cliCommand
                + " --team " + TmuxBackend.quote(teamName)
                + " --member " + TmuxBackend.quote(member.agentId())
                + " --spawn-task " + TmuxBackend.quote(task);
        return "osascript -e 'tell application \"iTerm2\" to create window with default profile command \""
                + shellCommand.replace("\"", "\\\"") + "\"'";
    }

    /** 构造唤醒命令字符串：向目标 session 写入空行触发重轮询。 */
    public String buildWakeCommand(String target) {
        return "osascript -e 'tell application \"iTerm2\" to tell session id \""
                + target + "\" to write text \"\"'";
    }

    /** 构造停止命令字符串：关闭目标 session。 */
    public String buildStopCommand(String target) {
        return "osascript -e 'tell application \"iTerm2\" to tell session id \""
                + target + "\" to close'";
    }

    @Override
    public MemberHandle spawn(TeamRuntime rt, TeamMember member, String task) {
        if (!available()) {
            throw new TeamException("后端 ITERM2 不可用: " + describe());
        }
        String target = "session-" + member.agentId();
        String shellCommand = cliCommand
                + " --team " + TmuxBackend.quote(rt.team().name())
                + " --member " + TmuxBackend.quote(member.agentId())
                + " --spawn-task " + TmuxBackend.quote(task);
        execute(List.of("osascript", "-e",
                "tell application \"iTerm2\" to create window with default profile command \""
                        + shellCommand.replace("\"", "\\\"") + "\""));
        return new MemberHandle("osascript", target, "iTerm2 pane 已创建: " + shellCommand);
    }

    @Override
    public void wake(TeamRuntime rt, TeamMember member) {
        if (!available()) {
            throw new TeamException("后端 ITERM2 不可用: " + describe());
        }
        execute(List.of("osascript", "-e",
                "tell application \"iTerm2\" to tell session id \"session-"
                        + member.agentId() + "\" to write text \"\""));
    }

    @Override
    public void stop(TeamRuntime rt, TeamMember member) {
        execute(List.of("osascript", "-e",
                "tell application \"iTerm2\" to tell session id \"session-"
                        + member.agentId() + "\" to close"));
    }

    private static void execute(List<String> args) {
        try {
            new ProcessBuilder(args).inheritIO().start();
        } catch (IOException e) {
            throw new TeamException("osascript 命令执行失败: " + e.getMessage());
        }
    }
}
