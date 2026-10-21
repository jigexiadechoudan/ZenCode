package dev.zencode.tool.impl;

import dev.zencode.tool.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 通过系统默认 shell 执行命令，支持管道和重定向。
 * <p>
 * 标准输出和标准错误分别捕获，各自最多返回 32 KiB；
 * 命令默认超时 120 秒，超时或取消时终止命令及其子进程。
 * </p>
 */
public final class BashTool extends BaseTool {
    private static final String PARAM_COMMAND = "command";
    private static final long DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_BYTES = 32 * 1024;
    private static final long POLL_INTERVAL_MILLIS = 100;

    private final WorkspacePathResolver pathResolver;

    public BashTool(WorkspacePathResolver pathResolver) {
        super("Bash",
                "通过 Windows Shell（cmd）执行命令，工作目录为工作根目录，支持管道和重定向。"
                        + "仅用于必须通过系统 shell 执行的命令（构建、测试、git 等）；不代替专用工具："
                        + "读取用 ReadFile、找路径用 Glob、搜内容用 Grep、改文件用 EditFile。"
                        + "命令默认 120 秒超时，超时或取消时终止命令及其子进程。",
                ToolCategory.COMMAND, ToolPermission.REQUIRES_CONFIRMATION,
                jsonSchema(Map.of(
                        PARAM_COMMAND, stringProp("要执行的命令")
                ), java.util.List.of(PARAM_COMMAND)));
        this.pathResolver = pathResolver;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String command = requiredString(args, PARAM_COMMAND);

        if (ctx.isCancelled()) {
            return ToolResult.cancelled(ctx.callId(), name(), "命令已取消");
        }

        // 选择默认 shell
        String[] shellCommand = getShellCommand(command);

        ProcessBuilder pb = new ProcessBuilder(shellCommand);
        pb.directory(pathResolver.workspaceRoot().toFile());
        pb.redirectErrorStream(false); // 分别捕获 stdout/stderr

        try {
            Process process = pb.start();

            // 异步读取 stdout 和 stderr
            StreamGobbler stdoutGobbler = new StreamGobbler(process.getInputStream());
            StreamGobbler stderrGobbler = new StreamGobbler(process.getErrorStream());
            Thread stdoutThread = new Thread(stdoutGobbler, "bash-stdout");
            Thread stderrThread = new Thread(stderrGobbler, "bash-stderr");
            stdoutThread.setDaemon(true);
            stderrThread.setDaemon(true);
            stdoutThread.start();
            stderrThread.start();

            // 短周期等待：每个周期检查共享取消信号与线程中断，超时或取消时及时终止进程树
            final long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(DEFAULT_TIMEOUT_SECONDS);
            boolean finished = false;
            try {
                while (!finished) {
                    if (ctx.isCancelled()) {
                        killTree(process);
                        String partial = stdoutGobbler.getOutput();
                        return ToolResult.cancelled(ctx.callId(), name(),
                                "命令已取消" + (partial.isEmpty() ? "" : "（部分输出已保留）"));
                    }
                    if (System.nanoTime() >= deadline) {
                        killTree(process);
                        String partial = stdoutGobbler.getOutput();
                        String partialErr = stderrGobbler.getOutput();
                        StringBuilder result = new StringBuilder();
                        if (!partial.isEmpty()) {
                            result.append("stdout:\n").append(partial).append("\n");
                        }
                        if (!partialErr.isEmpty()) {
                            result.append("stderr:\n").append(partialErr);
                        }
                        return ToolResult.timeout(ctx.callId(), name(),
                                "命令执行超时（" + DEFAULT_TIMEOUT_SECONDS + "秒）\n" + result);
                    }
                    finished = process.waitFor(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                killTree(process);
                return ToolResult.cancelled(ctx.callId(), name(), "命令被中断");
            }

            // 等待读取线程完成
            try {
                stdoutThread.join(2000);
                stderrThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            int exitCode = process.exitValue();

            // 对 stdout/stderr 分别应用 32 KiB 限制
            ToolOutputLimiter.LimitResult stdoutLimited = ToolOutputLimiter.limitBashOutput(stdoutGobbler.getOutput());
            ToolOutputLimiter.LimitResult stderrLimited = ToolOutputLimiter.limitBashOutput(stderrGobbler.getOutput());

            // 构建结果
            StringBuilder result = new StringBuilder();
            if (!stdoutLimited.text().isEmpty()) {
                result.append(stdoutLimited.text());
            }
            if (!stderrLimited.text().isEmpty()) {
                if (result.length() > 0) result.append("\n");
                result.append("stderr:\n").append(stderrLimited.text());
            }

            boolean truncated = stdoutLimited.truncated() || stderrLimited.truncated();

            if (exitCode != 0) {
                String errorMsg = "命令退出码: " + exitCode;
                if (result.length() > 0) {
                    errorMsg += "\n" + result;
                }
                return ToolResult.error(ctx.callId(), name(), errorMsg);
            }

            if (truncated) {
                return ToolResult.truncated(ctx.callId(), name(), result.toString());
            }

            return ToolResult.success(ctx.callId(), name(), result.toString());

        } catch (IOException e) {
            return ToolResult.error(ctx.callId(), name(), "启动命令失败: " + e.getMessage());
        }
    }

    /** 获取平台默认 shell 命令。 */
    private static String[] getShellCommand(String command) {
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("windows")) {
            // Windows: cmd /c
            return new String[]{"cmd.exe", "/c", command};
        } else {
            // Unix/Linux/Mac: sh -c
            return new String[]{"/bin/sh", "-c", command};
        }
    }

    /** 终止进程树：先销毁后代进程，再销毁父进程；等待受控时间后强制清理残留进程。 */
    private static void killTree(Process process) {
        if (process == null || !process.isAlive()) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 流读取器：独立线程读取 InputStream 并限制为 32 KiB。 */
    private static final class StreamGobbler implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile boolean overLimit;

        StreamGobbler(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            byte[] buf = new byte[4096];
            try {
                int bytesRead;
                while ((bytesRead = input.read(buf)) != -1) {
                    if (overLimit) continue;
                    if (buffer.size() + bytesRead > MAX_OUTPUT_BYTES) {
                        int allowed = MAX_OUTPUT_BYTES - buffer.size();
                        if (allowed > 0) {
                            buffer.write(buf, 0, allowed);
                        }
                        overLimit = true;
                    } else {
                        buffer.write(buf, 0, bytesRead);
                    }
                }
            } catch (IOException ignored) {
                // 流关闭时忽略
            }
        }

        String getOutput() {
            return decodeSmart(buffer.toByteArray());
        }

        /**
         * 智能解码：优先按 UTF-8 解码；遇到非法字节序列（如 Windows cmd 的 GBK/系统
         * 代码页输出）时回退到系统原生编码，避免乱码。
         */
        private static String decodeSmart(byte[] bytes) {
            CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                return utf8.decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException malformedUtf8) {
                return new String(bytes, nativeCharset());
            }
        }

        private static Charset nativeCharset() {
            String name = System.getProperty("sun.jnu.encoding");
            if (name != null && !name.isBlank()) {
                try {
                    return Charset.forName(name);
                } catch (RuntimeException ignored) {
                    // 非法编码名时回退默认
                }
            }
            return Charset.defaultCharset();
        }
    }
}