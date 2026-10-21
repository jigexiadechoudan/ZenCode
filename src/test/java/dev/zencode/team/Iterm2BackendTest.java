package dev.zencode.team;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * iTerm2 后端命令构造测试（纯函数断言；仅 macOS，真实 pane 端到端受阻）。
 */
class Iterm2BackendTest {

    private TeamMember member(String name, String agentId) {
        return new TeamMember(agentId, name, "", MemberStatus.SPAWNED,
                "C:/work/team", BackendType.ITERM2, false, false, null, 20, 1L);
    }

    @Test
    void spawnCommandContainsExpectedFragments() {
        Iterm2Backend backend = new Iterm2Backend("zencode");
        String cmd = backend.buildSpawnCommand("alpha", member("explorer", "m-abc"), "实现排序");
        assertTrue(cmd.startsWith("osascript -e"));
        assertTrue(cmd.contains("iTerm2"));
        assertTrue(cmd.contains("create window"));
        // osascript 内嵌命令的引号被转义为 \"
        assertTrue(cmd.contains("--team \\\"alpha\\\""));
        assertTrue(cmd.contains("--member \\\"m-abc\\\""));
        assertTrue(cmd.contains("--spawn-task \\\"实现排序\\\""));
    }

    @Test
    void wakeCommandTargetsSession() {
        Iterm2Backend backend = new Iterm2Backend();
        String cmd = backend.buildWakeCommand("session-m-abc");
        assertTrue(cmd.contains("iTerm2"));
        assertTrue(cmd.contains("session-m-abc"));
        assertTrue(cmd.contains("write text"));
    }

    @Test
    void stopCommandTargetsSession() {
        Iterm2Backend backend = new Iterm2Backend();
        String cmd = backend.buildStopCommand("session-m-abc");
        assertTrue(cmd.contains("session-m-abc"));
        assertTrue(cmd.contains("close"));
    }

    @Test
    void typeAndDescribe() {
        Iterm2Backend backend = new Iterm2Backend();
        assertEquals(BackendType.ITERM2, backend.type());
        assertFalse(backend.describe().isBlank());
        assertTrue(backend.describe().contains("macOS"));
    }
}
