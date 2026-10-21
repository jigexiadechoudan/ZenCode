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
 * WriteFile 工具测试。
 */
class WriteFileToolTest {
    @TempDir
    Path tempDir;

    private WriteFileTool tool;
    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        tool = new WriteFileTool(resolver);
        ctx = new ToolExecutionContext(tempDir, "call-1");
    }

    @Test
    void writesNewFile() {
        ToolResult result = tool.execute(Map.of("path", "new.txt", "content", "hello world"), ctx);
        assertTrue(result.success());
        assertTrue(Files.exists(tempDir.resolve("new.txt")));
    }

    @Test
    void overwritesExistingFile() throws IOException {
        Path file = tempDir.resolve("existing.txt");
        Files.writeString(file, "original");
        ToolResult result = tool.execute(Map.of("path", "existing.txt", "content", "replaced"), ctx);
        assertTrue(result.success());
        assertEquals("replaced", Files.readString(file));
    }

    @Test
    void createsParentDirectories() {
        ToolResult result = tool.execute(Map.of("path", "a/b/c/deep.txt", "content", "deep"), ctx);
        assertTrue(result.success());
        assertTrue(Files.exists(tempDir.resolve("a/b/c/deep.txt")));
    }

    @Test
    void keepsLFLineEnding() throws IOException {
        Path file = tempDir.resolve("lf.txt");
        Files.writeString(file, "line1\nline2\n", StandardCharsets.UTF_8);
        tool.execute(Map.of("path", "lf.txt", "content", "new1\nnew2\n"), ctx);
        byte[] bytes = Files.readAllBytes(file);
        // 检查是 LF 而不是 CRLF
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertEquals("new1\nnew2\n", content);
    }

    @Test
    void keepsCRLFLineEnding() throws IOException {
        Path file = tempDir.resolve("crlf.txt");
        Files.writeString(file, "line1\r\nline2\r\n", StandardCharsets.UTF_8);
        tool.execute(Map.of("path", "crlf.txt", "content", "new1\r\nnew2\r\n"), ctx);
        byte[] bytes = Files.readAllBytes(file);
        String content = new String(bytes, StandardCharsets.UTF_8);
        // CRLF 检测：如果原文件是 CRLF，写入应保持 CRLF
        assertTrue(content.contains("\r\n"), "CRLF 文件写入后应保持 CRLF 换行");
    }

    @Test
    void rejectsOutOfBoundsPath() {
        ToolResult result = tool.execute(Map.of("path", "../outside.txt", "content", "test"), ctx);
        assertFalse(result.success());
        assertTrue(result.content().contains("越界"));
    }

    @Test
    void missingPathParameterReturnsError() {
        ToolResult result = tool.execute(Map.of("content", "test"), ctx);
        assertFalse(result.success());
        assertEquals(ToolResult.Status.ERROR, result.status());
    }

    @Test
    void missingContentParameterReturnsError() {
        ToolResult result = tool.execute(Map.of("path", "test.txt"), ctx);
        assertFalse(result.success());
        assertEquals(ToolResult.Status.ERROR, result.status());
    }

    @Test
    void writesEmptyContent() {
        ToolResult result = tool.execute(Map.of("path", "empty.txt", "content", ""), ctx);
        assertTrue(result.success());
        assertTrue(Files.exists(tempDir.resolve("empty.txt")));
    }

    @Test
    void writesChineseContent() {
        ToolResult result = tool.execute(Map.of("path", "chinese.txt", "content", "你好世界"), ctx);
        assertTrue(result.success());
        assertTrue(Files.exists(tempDir.resolve("chinese.txt")));
    }
}