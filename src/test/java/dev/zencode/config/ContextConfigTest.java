package dev.zencode.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextConfigTest {

    @Test
    void defaultsHaveSaneValues() {
        ContextConfig c = ContextConfig.defaults();
        assertEquals(200_000L, c.windowTokens());
        assertEquals(0.8, c.compactRatio());
        assertEquals(4096, c.singleResultThresholdBytes());
        assertEquals(32 * 1024, c.roundTotalThresholdBytes());
        assertEquals(3, c.maxConsecutiveSummaryFailures());
    }

    @Test
    void rejectsNonPositiveWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContextConfig(0, 0.8, 4096, 32768, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextConfig(-1, 0.8, 4096, 32768, 3));
    }

    @Test
    void rejectsInvalidRatio() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContextConfig(200000, 0, 4096, 32768, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextConfig(200000, 1.5, 4096, 32768, 3));
    }

    @Test
    void rejectsNonPositiveThresholds() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContextConfig(200000, 0.8, 0, 32768, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextConfig(200000, 0.8, 4096, 0, 3));
    }

    @Test
    void rejectsNonPositiveBreaker() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContextConfig(200000, 0.8, 4096, 32768, 0));
    }
}
