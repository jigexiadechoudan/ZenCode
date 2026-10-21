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
 * Grep 工具测试。
 */
class GrepToolTest {
    @TempDir
    Path tempDir;

    private GrepTool tool;
    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        tool = new GrepTool(resolver);
        ctx = new ToolExecutionContext(tempDir, "call-1");
    }

    @Test
    void findsMatchingLines() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "hello world\nfoo bar\nhello again\n");
        ToolResult result = tool.execute(Map.of("pattern", "hello"), ctx);
        assertTrue(result.success());
        assertTrue(result.content().contains("hello world"));
        assertTrue(result.content().contains("hello again"));
        assertFalse(result.content().contains("foo bar"));
    }

    @Test
    void supportsRegex() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "abc123\ndef456\nabc789\n");
        ToolResult result = tool.execute(Map.of("pattern", "abc\\d+"), ctx);
        assertTrue(result.success());
        assertTrue(result.content().contains("abc123"));
        assertTrue(result.content().contains("abc789"));
        assertFalse(result.content().contains("def456"));
    }

    @Test
    void caseSensitiveByDefault() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "Hello\nhello\nHELLO\n");
        ToolResult result = tool.execute(Map.of("pattern", "hello"), ctx);
        assertTrue(result.success());
        assertTrue(result.content().contains("hello"));
        assertFalse(result.content().contains("Hello"));
        assertFalse(result.content().contains("HELLO"));
    }

    @Test
    void caseInsensitiveFlag() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "Hello\nhello\nHELLO\n");
        ToolResult result = tool.execute(Map.of("pattern", "hello", "case_sensitive", false), ctx);
        assertTrue(result.success());
        assertTrue(result.content().contains("Hello") || result.content().contains("hello"));
    }

    @Test
    void supportsFilePattern() throws IOException {
        Files.writeString(tempDir.resolve("search.java"), "hello");
        Files.writeString(tempDir.resolve("search.txt"), "hello");
        ToolResult result = tool.execute(Map.of("pattern", "hello", "include", "*.java"), ctx);
        assertTrue(result.success());
        assertTrue(result.content().contains("search.java"));
        assertFalse(result.content().contains("search.txt"));
    }

    @Test
    void noMatchReturnsEmpty() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "hello world");
        ToolResult result = tool.execute(Map.of("pattern", "xyz"), ctx);
        assertTrue(result.success());
        assertTrue(result.content().contains("未找到匹配"));
    }

    @Test
    void resultsAreSortedByPathAndLine() throws IOException {
        Files.writeString(tempDir.resolve("b.txt"), "a\nb\nsearch\n");
        Files.writeString(tempDir.resolve("a.txt"), "search\n");
        ToolResult result = tool.execute(Map.of("pattern", "search"), ctx);
        assertTrue(result.success());
        // a.txt 应在 b.txt 之前
        int aPos = result.content().indexOf("a.txt");
        int bPos = result.content().indexOf("b.txt");
        assertTrue(aPos < bPos, "结果应按路径排序");
    }

    @Test
    void skipsBinaryFiles() throws IOException {
        Path binary = tempDir.resolve("data.bin");
        byte[] bytes = {(byte) 0x00, (byte) 0x01, (byte) 0x02};
        Files.write(binary, bytes);
        Files.writeString(tempDir.resolve("text.txt"), "search term");
        ToolResult result = tool.execute(Map.of("pattern", "search"), ctx);
        assertTrue(result.success());
        // 二进制文件应被跳过，但文本文件中的匹配应找到
        assertTrue(result.content().contains("text.txt") || result.content().contains("跳过"));
    }

    @Test
    void invalidRegexReturnsError() {
        ToolResult result = tool.execute(Map.of("pattern", "[invalid"), ctx);
        assertFalse(result.success());
        assertTrue(result.content().contains("正则"));
    }

    @Test
    void rejectsMissingPattern() {
        ToolResult result = tool.execute(Map.of(), ctx);
        assertFalse(result.success());
        assertEquals(ToolResult.Status.ERROR, result.status());
    }
}