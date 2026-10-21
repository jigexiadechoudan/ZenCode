package dev.zencode.team;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tmux 后端命令构造测试（纯函数断言；真实窗格端到端在 Windows 受阻，不冒充验收）。
 */
class TmuxBackendTest {

    private TeamMember member(String name, String agentId) {
        return new TeamMember(agentId, name, "", MemberStatus.SPAWNED,
                "C:/work/team", BackendType.TMUX, false, false, null, 20, 1L);
    }

    @Test
    void spawnCommandContainsExpectedFragments() {
        TmuxBackend backend = new TmuxBackend("zencode");
        String cmd = backend.buildSpawnCommand("alpha", member("explorer", "m-abc"), "C:/work/team", "实现排序");
        assertTrue(cmd.startsWith("tmux new-window"));
        assertTrue(cmd.contains("-n \"explorer\""));
        assertTrue(cmd.contains("-c \"C:/work/team\""));
        assertTrue(cmd.contains("--team \"alpha\""));
        assertTrue(cmd.contains("--member \"m-abc\""));
        assertTrue(cmd.contains("--spawn-task \"实现排序\""));
    }

    @Test
    void spawnCommandEscapesQuotesInTask() {
        TmuxBackend backend = new TmuxBackend("zencode");
        String cmd = backend.buildSpawnCommand("alpha", member("e", "m-1"), "C:/w", "说 \"你好\"");
        assertTrue(cmd.contains("--spawn-task \"说 \\\"你好\\\"\""), "任务内引号应被转义");
    }

    @Test
    void customCliCommandUsed() {
        TmuxBackend backend = new TmuxBackend("C:/tools/zencode.bat");
        String cmd = backend.buildSpawnCommand("alpha", member("e", "m-1"), "C:/w", "t");
        assertTrue(cmd.contains("C:/tools/zencode.bat"));
    }

    @Test
    void wakeCommandTargetsWindow() {
        TmuxBackend backend = new TmuxBackend();
        String cmd = backend.buildWakeCommand("mew-explorer");
        assertEquals("tmux send-keys -t \"mew-explorer\" Enter", cmd);
    }

    @Test
    void stopCommandTargetsWindow() {
        TmuxBackend backend = new TmuxBackend();
        String cmd = backend.buildStopCommand("mew-explorer");
        assertEquals("tmux kill-window -t \"mew-explorer\"", cmd);
    }

    @Test
    void typeAndDescribe() {
        TmuxBackend backend = new TmuxBackend();
        assertEquals(BackendType.TMUX, backend.type());
        assertFalse(backend.describe().isBlank());
    }
}
