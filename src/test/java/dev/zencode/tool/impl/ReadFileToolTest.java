package dev.zencode.tool.impl;

import dev.zencode.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReadFile 工具测试。
 */
class ReadFileToolTest {
    @TempDir
    Path tempDir;

    private ReadFileTool tool;
    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        tool = new ReadFileTool(resolver);
        ctx = new ToolExecutionContext(tempDir, "call-1");
    }

    @Test
    void readsFullFile() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "第一行\n第二行\n第三行\n");
        ToolResult result = tool.execute(Map.of("path", "test.txt"), ctx);
        assertTrue(result.success());
        assertTrue(result.content().contains("第一行"));
        assertTrue(result.content().contains("第二行"));
        assertTrue(result.content().contains("第三行"));
        assertTrue(result.content().contains("3 行"));
    }

    @Test
    void readsWithLineRange() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "行1\n行2\n行3\n行4\n行5\n");
        ToolResult result = tool.execute(Map.of("path", "test.txt", "start_line", 2, "end_line", 4), ctx);
        assertTrue(result.success());
        assertTrue(result.content().contains("行2"));
        assertTrue(result.content().contains("行4"));
        assertFalse(result.content().contains("行1"));
        assertFalse(result.content().contains("行5"));
    }

    @Test
    void rejectsNonExistentFile() {
        ToolResult result = tool.execute(Map.of("path", "no-such-file.txt"), ctx);
        assertFalse(result.success());
        assertTrue(result.content().contains("不存在"));
    }

    @Test
    void rejectsDirectory() throws IOException {
        Path dir = tempDir.resolve("subdir");
        Files.createDirectories(dir);
        ToolResult result = tool.execute(Map.of("path", "subdir"), ctx);
        assertFalse(result.success());
        assertTrue(result.content().contains("目录"));
    }

    @Test
    void rejectsNonUtf8File() throws IOException {
        Path file = tempDir.resolve("binary.bin");
        // 写入非 UTF-8 字节序列
        byte[] bytes = {(byte) 0xFF, (byte) 0xFE, (byte) 0x00, (byte) 0x01};
        Files.write(file, bytes);
        ToolResult result = tool.execute(Map.of("path", "binary.bin"), ctx);
        assertFalse(result.success());
        assertTrue(result.content().contains("UTF-8") || result.content().contains("失败"));
    }

    @Test
    void rejectsOutOfBoundsPath() {
        ToolResult result = tool.execute(Map.of("path", "../outside.txt"), ctx);
        assertFalse(result.success());
        assertTrue(result.content().contains("越界"));
    }

    @Test
    void rejectsInvalidLineRange() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "a\nb\nc\n");
        ToolResult result = tool.execute(Map.of("path", "test.txt", "start_line", 5), ctx);
        assertFalse(result.success());
        assertTrue(result.content().contains("超出"));
    }

    @Test
    void outputContainsLineNumbers() throws IOException {
        Path file = tempDir.resolve("numbered.txt");
        Files.writeString(file, "aaa\nbbb\nccc\n");
        ToolResult result = tool.execute(Map.of("path", "numbered.txt"), ctx);
        assertTrue(result.success());
        assertTrue(result.content().contains("  1  aaa"));
        assertTrue(result.content().contains("  2  bbb"));
    }

    @Test
    void missingPathParameterReturnsError() {
        ToolResult result = tool.execute(Map.of(), ctx);
        assertFalse(result.success());
        assertEquals(ToolResult.Status.ERROR, result.status());
    }
}