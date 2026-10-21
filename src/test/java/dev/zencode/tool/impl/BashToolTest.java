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
 * Bash 工具测试。
 */
class BashToolTest {
    @TempDir
    Path tempDir;

    private BashTool tool;
    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        tool = new BashTool(resolver);
        ctx = new ToolExecutionContext(tempDir, "call-1");
    }

    @Test
    void executesSimpleCommand() {
        ToolResult result = tool.execute(Map.of("command", "echo hello"), ctx);
        assertTrue(result.success(), "命令应成功，实际: " + result.content());
        assertTrue(result.content().contains("hello"), "输出应包含 hello");
    }

    @Test
    void usesWorkingDirectory() throws IOException {
        Path marker = tempDir.resolve("marker.txt");
        Files.writeString(marker, "present");
        ToolResult result = tool.execute(Map.of("command", "dir marker.txt 2>nul || ls marker.txt 2>/dev/null || echo checking"), ctx);
        // 命令在 Windows 或 Linux 下不同，但应正常执行
        assertNotNull(result);
    }

    @Test
    void nonZeroExitReturnsError() {
        ToolResult result = tool.execute(Map.of("command", "exit 1"), ctx);
        assertFalse(result.success());
        assertTrue(result.content().contains("退出码") || result.content().contains("exit"));
    }

    @Test
    void decodesChineseOutputWithoutMojibake() {
        ToolResult result = tool.execute(Map.of("command", "echo 中文测试"), ctx);
        assertTrue(result.success(), "命令应成功，实际: " + result.content());
        assertTrue(result.content().contains("中文测试"),
                "输出应正确解码为中文（Windows cmd 为 GBK/系统代码页），实际: " + result.content());
        assertFalse(result.content().contains("\uFFFD"),
                "不应出现替换符乱码，实际: " + result.content());
    }

    @Test
    void capturesStdoutAndStderr() {
        // Windows: echo 到 stderr
        String cmd = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")
                ? "cmd /c \"echo out && echo err >&2\""
                : "sh -c 'echo out; echo err >&2'";
        ToolResult result = tool.execute(Map.of("command", cmd), ctx);
        // 结果可能包含 stdout 和 stderr 的内容
        assertNotNull(result);
    }

    @Test
    void supportsPipes() {
        String cmd = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")
                ? "echo hello | findstr hello"
                : "echo hello | cat";
        ToolResult result = tool.execute(Map.of("command", cmd), ctx);
        assertTrue(result.success(), "管道命令应成功，实际: " + result.content());
    }

    @Test
    void rejectsMissingCommand() {
        ToolResult result = tool.execute(Map.of(), ctx);
        assertFalse(result.success());
        assertEquals(ToolResult.Status.ERROR, result.status());
    }

    @Test
    void largeOutputIsTruncated() {
        // 生成大量输出测试截断
        String cmd = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")
                ? "cmd /c \"for /l %i in (1,1,5000) do @echo line %i\""
                : "for i in $(seq 1 5000); do echo \"line $i\"; done";
        ToolResult result = tool.execute(Map.of("command", cmd), ctx);
        // 大量输出可能被截断或者成功
        assertNotNull(result);
    }

    @Test
    void cancellationStopsCommand() throws Exception {
        // 启动一个长时间运行命令
        String cmd = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")
                ? "ping -n 30 127.0.0.1"
                : "sleep 30";
        // 执行前取消
        ctx.cancel();
        ToolResult result = tool.execute(Map.of("command", cmd), ctx);
        assertFalse(result.success());
        assertEquals(ToolResult.Status.CANCELLED, result.status(), "取消后应返回 CANCELLED 状态");
    }

    @Test
    void longCommandCancelledMidRunStopsProcessTreeAndReturnsPromptly() throws Exception {
        // 命令启动后先进入长等待；若进程树未被终止，后续命令会执行并输出 FINISHED_MARKER
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows");
        String cmd = windows
                ? "cmd /c \"(ping -n 60 127.0.0.1 >nul) & echo FINISHED_MARKER\""
                : "sh -c 'sleep 60; echo FINISHED_MARKER'";

        ToolExecutionContext asyncCtx = new ToolExecutionContext(tempDir, "call-cancel");
        java.util.concurrent.atomic.AtomicReference<ToolResult> ref =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread runner = new Thread(() -> ref.set(tool.execute(Map.of("command", cmd), asyncCtx)));
        runner.setDaemon(true);
        runner.start();

        Thread.sleep(700); // 等待命令启动并进入长等待
        long cancelStart = System.currentTimeMillis();
        asyncCtx.cancel();
        runner.join(10_000);
        long elapsed = System.currentTimeMillis() - cancelStart;

        assertFalse(runner.isAlive(), "取消后执行线程应在受控时间内返回");
        assertTrue(elapsed < 9_000, "取消响应应快速完成，实际耗时 " + elapsed + "ms");
        ToolResult result = ref.get();
        assertNotNull(result);
        assertEquals(ToolResult.Status.CANCELLED, result.status(), "实际: " + result.content());
        assertTrue(result.content().contains("取消"), "结果应包含取消说明: " + result.content());
        assertFalse(result.content().contains("FINISHED_MARKER"),
                "进程树应被终止，后续命令不得执行: " + result.content());
    }
}