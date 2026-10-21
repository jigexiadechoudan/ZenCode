package dev.zencode.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.zencode.tool.BaseTool;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolOutputLimiter;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 目录型 Skill 自带工具：schema 来自 tool.json，执行时用对应解释器运行脚本。
 * <p>
 * 参数以 JSON 写入临时文件，经环境变量 {@code ZENCODE_SKILL_ARGS} 传给脚本；
 * 工作目录为 skill 目录。脚本默认 120 秒超时，超时或取消时终止进程树。
 * </p>
 */
public final class SkillTool extends BaseTool {

    private static final String ARGS_ENV = "ZENCODE_SKILL_ARGS";
    private static final long DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_BYTES = 32 * 1024;
    private static final long POLL_INTERVAL_MILLIS = 100;

    private final SkillToolSpec spec;
    private final Path skillDir;

    public SkillTool(SkillToolSpec spec, Path skillDir) {
        super(spec.name(), spec.description(), ToolCategory.COMMAND,
                ToolPermission.REQUIRES_CONFIRMATION, spec.schema());
        this.spec = spec;
        this.skillDir = skillDir;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        if (ctx.isCancelled()) {
            return ToolResult.cancelled(ctx.callId(), name(), "工具调用已取消");
        }

        Path argsFile = null;
        try {
            argsFile = Files.createTempFile("zencode-skill-args-", ".json");
            Files.writeString(argsFile, mapper.writeValueAsString(args), StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            return ToolResult.invalidArgument(ctx.callId(), name(), "参数序列化失败: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.error(ctx.callId(), name(), "无法创建参数文件: " + e.getMessage());
        }

        List<String> command = switch (spec.kind()) {
            case POWERSHELL -> List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy",
                    "Bypass", "-File", spec.script().toString());
            case CMD -> List.of("cmd.exe", "/c", spec.script().toString());
        };

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(skillDir.toFile());
        pb.environment().put(ARGS_ENV, argsFile.toString());

        try {
            Process process = pb.start();
            StreamGobbler stdoutGobbler = new StreamGobbler(process.getInputStream());
            StreamGobbler stderrGobbler = new StreamGobbler(process.getErrorStream());
            Thread stdoutThread = new Thread(stdoutGobbler, "skill-stdout");
            Thread stderrThread = new Thread(stderrGobbler, "skill-stderr");
            stdoutThread.setDaemon(true);
            stderrThread.setDaemon(true);
            stdoutThread.start();
            stderrThread.start();

            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEFAULT_TIMEOUT_SECONDS);
            boolean finished = false;
            try {
                while (!finished) {
                    if (ctx.isCancelled()) {
                        killTree(process);
                        String partial = stdoutGobbler.getOutput();
                        return ToolResult.cancelled(ctx.callId(), name(),
                                "脚本已取消" + (partial.isEmpty() ? "" : "（部分输出已保留）"));
                    }
                    if (System.nanoTime() >= deadline) {
                        killTree(process);
                        String partial = stdoutGobbler.getOutput();
                        String partialErr = stderrGobbler.getOutput();
                        StringBuilder result = new StringBuilder();
                        if (!partial.isEmpty()) result.append("stdout:\n").append(partial).append("\n");
                        if (!partialErr.isEmpty()) result.append("stderr:\n").append(partialErr);
                        return ToolResult.timeout(ctx.callId(), name(),
                                "脚本执行超时（" + DEFAULT_TIMEOUT_SECONDS + "秒）\n" + result);
                    }
                    finished = process.waitFor(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                killTree(process);
                return ToolResult.cancelled(ctx.callId(), name(), "脚本被中断");
            }

            try {
                stdoutThread.join(2000);
                stderrThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            int exitCode = process.exitValue();
            ToolOutputLimiter.LimitResult stdoutLimited = ToolOutputLimiter.limitBashOutput(stdoutGobbler.getOutput());
            ToolOutputLimiter.LimitResult stderrLimited = ToolOutputLimiter.limitBashOutput(stderrGobbler.getOutput());

            StringBuilder result = new StringBuilder();
            if (!stdoutLimited.text().isEmpty()) result.append(stdoutLimited.text());
            if (!stderrLimited.text().isEmpty()) {
                if (result.length() > 0) result.append("\n");
                result.append("stderr:\n").append(stderrLimited.text());
            }
            boolean truncated = stdoutLimited.truncated() || stderrLimited.truncated();

            if (exitCode != 0) {
                String errorMsg = "脚本退出码: " + exitCode;
                if (result.length() > 0) errorMsg += "\n" + result;
                return ToolResult.error(ctx.callId(), name(), errorMsg);
            }
            if (truncated) {
                return ToolResult.truncated(ctx.callId(), name(), result.toString());
            }
            return ToolResult.success(ctx.callId(), name(), result.toString());
        } catch (IOException e) {
            return ToolResult.error(ctx.callId(), name(), "启动脚本失败: " + e.getMessage());
        }
    }

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

    /** 流读取器：限制输出为 32 KiB，UTF-8 优先、系统编码回退。 */
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
                        if (allowed > 0) buffer.write(buf, 0, allowed);
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

        private static String decodeSmart(byte[] bytes) {
            CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                return utf8.decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException malformedUtf8) {
                String name = System.getProperty("sun.jnu.encoding");
                if (name != null && !name.isBlank()) {
                    try {
                        return new String(bytes, Charset.forName(name));
                    } catch (RuntimeException ignored) {
                        // 非法编码名时回退默认
                    }
                }
                return new String(bytes, Charset.defaultCharset());
            }
        }
    }
}
