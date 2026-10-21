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
 * EditFile 工具测试。
 */
class EditFileToolTest {
    @TempDir
    Path tempDir;

    private EditFileTool tool;
    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        tool = new EditFileTool(resolver);
        ctx = new ToolExecutionContext(tempDir, "call-1");
    }

    @Test
    void uniqueReplacementSucceeds() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world foo bar");
        ToolResult result = tool.execute(Map.of("path", "test.txt", "old_text", "world", "new_text", "zencode"), ctx);
        assertTrue(result.success());
        assertEquals("hello zencode foo bar", Files.readString(file));
    }

    @Test
    void noMatchReturnsError() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world");
        ToolResult result = tool.execute(Map.of("path", "test.txt", "old_text", "xyz", "new_text", "abc"), ctx);
        assertFalse(result.success());
        assertEquals("hello world", Files.readString(file));
    }

    @Test
    void multipleMatchesReturnsError() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello hello hello");
        ToolResult result = tool.execute(Map.of("path", "test.txt", "old_text", "hello", "new_text", "hi"), ctx);
        assertFalse(result.success());
        assertEquals("hello hello hello", Files.readString(file));
    }

    @Test
    void rejectsNonExistentFile() {
        ToolResult result = tool.execute(Map.of("path", "no-such.txt", "old_text", "a", "new_text", "b"), ctx);
        assertFalse(result.success());
    }

    @Test
    void rejectsDirectory() throws IOException {
        Path dir = tempDir.resolve("subdir");
        Files.createDirectories(dir);
        ToolResult result = tool.execute(Map.of("path", "subdir", "old_text", "a", "new_text", "b"), ctx);
        assertFalse(result.success());
    }

    @Test
    void keepsCRLFLineEnding() throws IOException {
        Path file = tempDir.resolve("crlf.txt");
        Files.writeString(file, "line1\r\nold_text\r\nline3\r\n", StandardCharsets.UTF_8);
        ToolResult result = tool.execute(Map.of("path", "crlf.txt", "old_text", "old_text", "new_text", "new_text"), ctx);
        assertTrue(result.success());
        byte[] bytes = Files.readAllBytes(file);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(content.contains("\r\n"), "CRLF 文件编辑后应保持 CRLF 换行");
        assertTrue(content.contains("new_text"), "替换应成功");
    }

    @Test
    void keepsLFLineEnding() throws IOException {
        Path file = tempDir.resolve("lf.txt");
        Files.writeString(file, "line1\nold_text\nline3\n", StandardCharsets.UTF_8);
        ToolResult result = tool.execute(Map.of("path", "lf.txt", "old_text", "old_text", "new_text", "new_text"), ctx);
        assertTrue(result.success());
        String content = Files.readString(file);
        assertEquals("line1\nnew_text\nline3\n", content);
    }

    @Test
    void rejectsNonUtf8File() throws IOException {
        Path file = tempDir.resolve("binary.bin");
        byte[] bytes = {(byte) 0xFF, (byte) 0xFE, (byte) 0x00, (byte) 0x01};
        Files.write(file, bytes);
        ToolResult result = tool.execute(Map.of("path", "binary.bin", "old_text", "a", "new_text", "b"), ctx);
        assertFalse(result.success());
    }

    @Test
    void countOccurrencesWorks() {
        assertEquals(1, EditFileTool.countOccurrences("hello world", "world"));
        assertEquals(0, EditFileTool.countOccurrences("hello world", "xyz"));
        assertEquals(3, EditFileTool.countOccurrences("aaa", "a"));
        assertEquals(2, EditFileTool.countOccurrences("aaaa", "aa"));
    }
}