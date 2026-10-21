package dev.zencode.memory;

import dev.zencode.config.ConfigException;
import dev.zencode.config.ConfigLoader;
import dev.zencode.cli.CliOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryConfigTest {

    @Test
    void defaultsHaveSaneValues() {
        MemoryConfig c = MemoryConfig.defaults();
        assertTrue(c.enabled());
        assertEquals(5, c.extractIntervalRounds());
        assertEquals(16384, c.memoryMaxChars());
        assertEquals(3, c.includeMaxDepth());
        assertEquals(24, c.sessionIdleHours());
        assertTrue(c.sessionArchive());
    }

    @Test
    void rejectsNonPositiveExtractInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryConfig(true, 0, 16384, 3, 24, true));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryConfig(true, -1, 16384, 3, 24, true));
    }

    @Test
    void rejectsNonPositiveMemoryMaxChars() {
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryConfig(true, 5, 0, 3, 24, true));
    }

    @Test
    void rejectsNonPositiveIncludeDepth() {
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryConfig(true, 5, 16384, 0, 24, true));
    }

    @Test
    void rejectsNonPositiveSessionIdleHours() {
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryConfig(true, 5, 16384, 3, 0, true));
    }

    @Test
    void parseMemoryDefaultWhenAbsent() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: key
                """);
        ConfigLoader loader = new ConfigLoader();
        ConfigLoader.LoadedConfig loaded = loader.load(CliOptions.parse(new String[]{"--config", file.toString()}));
        MemoryConfig c = loaded.appConfig().memory();
        assertTrue(c.enabled());
        assertEquals(5, c.extractIntervalRounds());
    }

    @Test
    void parseMemoryCustomValues() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: key
                memory:
                  enabled: false
                  extract_interval_rounds: 10
                  memory_max_chars: 8192
                  include_max_depth: 5
                  session_idle_hours: 48
                  session_archive: false
                """);
        ConfigLoader loader = new ConfigLoader();
        ConfigLoader.LoadedConfig loaded = loader.load(CliOptions.parse(new String[]{"--config", file.toString()}));
        MemoryConfig c = loaded.appConfig().memory();
        assertFalse(c.enabled());
        assertEquals(10, c.extractIntervalRounds());
        assertEquals(8192, c.memoryMaxChars());
        assertEquals(5, c.includeMaxDepth());
        assertEquals(48, c.sessionIdleHours());
        assertFalse(c.sessionArchive());
    }

    @Test
    void parseMemoryRejectsUnknownField() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: key
                memory:
                  unknown_field: true
                """);
        ConfigLoader loader = new ConfigLoader();
        assertThrows(ConfigException.class, () ->
                loader.load(CliOptions.parse(new String[]{"--config", file.toString()})));
    }

    @Test
    void appConfigDefaultsToMemoryDefaults() {
        dev.zencode.config.AppConfig appConfig = new dev.zencode.config.AppConfig(
                "test", java.util.List.of());
        MemoryConfig c = appConfig.memory();
        assertEquals(MemoryConfig.defaults(), c);
    }
}
