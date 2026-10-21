package dev.zencode.team;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 后端检测测试：in-process 恒可用；显式不可用报错不降级；AUTO 优先级；
 * 检测结果可读。
 */
class BackendDetectorTest {

    private BackendDetector detector(boolean tmux, boolean iterm2) {
        return new BackendDetector(() -> tmux, () -> iterm2);
    }

    @Test
    void inprocessAlwaysAvailable() {
        BackendDetector detector = detector(false, false);
        assertEquals(BackendType.INPROCESS, detector.detect(BackendType.INPROCESS));
        assertEquals(BackendType.INPROCESS, detector.detect(BackendType.AUTO));
    }

    @Test
    void autoPrefersTmuxThenIterm2() {
        assertEquals(BackendType.TMUX, detector(true, true).detect(BackendType.AUTO));
        assertEquals(BackendType.TMUX, detector(true, false).detect(BackendType.AUTO));
        assertEquals(BackendType.ITERM2, detector(false, true).detect(BackendType.AUTO));
        assertEquals(BackendType.INPROCESS, detector(false, false).detect(BackendType.AUTO));
    }

    @Test
    void explicitUnavailableThrowsReadableError() {
        TeamException tmuxError = assertThrows(TeamException.class,
                () -> detector(false, false).detect(BackendType.TMUX));
        assertTrue(tmuxError.getMessage().contains("TMUX"));
        assertTrue(tmuxError.getMessage().contains("不可用"));

        TeamException iterm2Error = assertThrows(TeamException.class,
                () -> detector(false, false).detect(BackendType.ITERM2));
        assertTrue(iterm2Error.getMessage().contains("ITERM2"));
        assertTrue(iterm2Error.getMessage().contains("不可用"));
    }

    @Test
    void explicitAvailableSucceeds() {
        assertEquals(BackendType.TMUX, detector(true, false).detect(BackendType.TMUX));
        assertEquals(BackendType.ITERM2, detector(false, true).detect(BackendType.ITERM2));
    }

    @Test
    void nullRequestedThrows() {
        assertThrows(TeamException.class, () -> detector(false, false).detect(null));
    }

    @Test
    void describeIsReadableAndNonEmpty() {
        BackendDetector detector = detector(false, false);
        assertFalse(detector.describe(BackendType.INPROCESS).isBlank());
        assertFalse(detector.describe(BackendType.TMUX).isBlank());
        assertFalse(detector.describe(BackendType.ITERM2).isBlank());
        assertTrue(detector.describe(BackendType.AUTO).contains("自动检测"));
    }
}
