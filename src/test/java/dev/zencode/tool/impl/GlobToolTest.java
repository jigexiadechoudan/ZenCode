package dev.zencode.tool.impl;

import dev.zencode.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Glob 工具测试。
 */
class GlobToolTest {
    @TempDir
    Path tempDir;

    private GlobTool tool;
    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        tool = new GlobTool(resolver);
        ctx = new ToolExecutionContext(tempDir, "call-1");
    }

    @Test
    void matchesAllJavaFiles() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main"));
        Files.writeString(tempDir.resolve("src/main/App.java"), "class App {}");
        Files.writeString(tempDir.resolve("src/main/Util.java"), "class Util {}");
        Files.writeString(tempDir.resolve("src/main/README.md"), "# readme");

        ToolResult result = tool.execute(Map.of("pattern", "**/*.java"), ctx);
        assertTrue(result.success());
        assertTrue(result.content().contains("App.java"));
        assertTrue(result.content().contains("Util.java"));
        assertFalse(result.content().contains("README.md"));
    }

    @Test
    void emptyMatchReturnsEmptyResult() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/file.txt"), "content");

        ToolResult result = tool.execute(Map.of("pattern", "**/*.py"), ctx);
        assertTrue(result.success());
        // 空匹配应成功但无内容
        assertNotNull(result.content());
    }

    @Test
    void supportsDirectoryGlob() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("src/test/java"));
        Files.createDirectories(tempDir.resolve("docs"));

        ToolResult result = tool.execute(Map.of("pattern", "src/*/java"), ctx);
        assertTrue(result.success());
        assertTrue(result.content().contains("src/main/java"));
        assertTrue(result.content().contains("src/test/java"));
    }

    @Test
    void resultsAreSorted() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/z.java"), "z");
        Files.writeString(tempDir.resolve("src/a.java"), "a");
        Files.writeString(tempDir.resolve("src/m.java"), "m");

        ToolResult result = tool.execute(Map.of("pattern", "src/*.java"), ctx);
        assertTrue(result.success());
        String[] lines = result.content().split("\n");
        // 检查排序
        assertTrue(lines[0].contains("a.java") || lines[0].contains("src/a.java"));
    }

    @Test
    void rejectsMissingPattern() {
        ToolResult result = tool.execute(Map.of(), ctx);
        assertFalse(result.success());
        assertEquals(ToolResult.Status.ERROR, result.status());
    }
}