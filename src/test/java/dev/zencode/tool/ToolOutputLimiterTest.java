package dev.zencode.tool;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具输出限制器测试。
 */
class ToolOutputLimiterTest {

    @Test
    void shortTextIsNotTruncated() {
        ToolOutputLimiter.LimitResult result = ToolOutputLimiter.limitText("简短文本");
        assertFalse(result.truncated());
        assertEquals("简短文本", result.text());
    }

    @Test
    void emptyTextIsNotTruncated() {
        ToolOutputLimiter.LimitResult result = ToolOutputLimiter.limitText("");
        assertFalse(result.truncated());
        assertEquals("", result.text());
    }

    @Test
    void nullTextIsNotTruncated() {
        ToolOutputLimiter.LimitResult result = ToolOutputLimiter.limitText(null);
        assertFalse(result.truncated());
        assertEquals("", result.text());
    }

    @Test
    void largeTextIsTruncatedOnLineBoundary() {
        // 构造超过 32 KiB 的文本
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            large.append("这是第 ").append(i).append(" 行 - 一些测试数据，用于填充输出。\n");
        }
        String text = large.toString();
        assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32 * 1024);

        ToolOutputLimiter.LimitResult result = ToolOutputLimiter.limitText(text);
        assertTrue(result.truncated());
        assertTrue(result.text().contains("...(输出截断"));
        // 检查截断后的字节数不超过 32 KiB
        assertTrue(result.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32 * 1024);
    }

    @Test
    void limitPathsShortList() {
        ToolOutputLimiter.LimitResult result = ToolOutputLimiter.limitPaths(List.of("a.txt", "b.txt"));
        assertFalse(result.truncated());
        assertTrue(result.text().contains("a.txt"));
        assertTrue(result.text().contains("b.txt"));
    }

    @Test
    void limitPathsEmptyList() {
        ToolOutputLimiter.LimitResult result = ToolOutputLimiter.limitPaths(List.of());
        assertFalse(result.truncated());
        assertEquals("", result.text());
    }

    @Test
    void limitPathsLargeListIsTruncated() {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            paths.add("very/long/path/prefix/for/testing/file_" + i + ".java");
        }
        ToolOutputLimiter.LimitResult result = ToolOutputLimiter.limitPaths(paths);
        assertTrue(result.truncated());
        assertTrue(result.text().contains("...(输出截断"));
        assertTrue(result.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32 * 1024);
    }

    @Test
    void limitBashOutputShort() {
        ToolOutputLimiter.LimitResult result = ToolOutputLimiter.limitBashOutput("hello\nworld");
        assertFalse(result.truncated());
        assertEquals("hello\nworld", result.text());
    }

    @Test
    void limitBashOutputLarge() {
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            large.append("line ").append(i).append(" with some data that makes it longer\n");
        }
        String text = large.toString();
        ToolOutputLimiter.LimitResult result = ToolOutputLimiter.limitBashOutput(text);
        assertTrue(result.truncated());
        assertTrue(result.text().contains("...(输出截断"));
        assertTrue(result.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32 * 1024);
    }
}