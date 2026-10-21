package dev.zencode.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CliOptionsTest {
    @Test
    void parsesSupportedOptions() {
        CliOptions options = CliOptions.parse(new String[]{"--config", "config.yaml", "--provider", "main"});
        assertEquals("config.yaml", options.configPath().toString());
        assertEquals("main", options.providerName());
    }

    @Test
    void parsesPermissionModeOption() {
        CliOptions options = CliOptions.parse(new String[]{"--permission-mode", "lenient"});
        assertEquals(dev.zencode.permission.PermissionMode.LENIENT, options.permissionMode());
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[]{"--permission-mode", "bogus"}));
    }

    @Test
    void rejectsUnknownAndDuplicateOptions() {
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[]{"--bad"}));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[]{"--provider", "a", "--provider", "b"}));
    }

    @Test
    void parsesMemberModeOptions() {
        CliOptions options = CliOptions.parse(new String[]{
                "--team", "alpha", "--member", "m-1234abcd", "--spawn-task", "实现排序函数"});
        assertEquals("alpha", options.team());
        assertEquals("m-1234abcd", options.member());
        assertEquals("实现排序函数", options.spawnTask());
    }

    @Test
    void memberModeOptionsRequireValue() {
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[]{"--team"}));
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[]{"--member", "--team", "x"}));
    }

    @Test
    void memberModeOptionsRejectDuplicate() {
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[]{"--team", "a", "--team", "b"}));
    }
}
