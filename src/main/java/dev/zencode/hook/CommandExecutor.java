package dev.zencode.hook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * command 执行器：通过系统默认 shell（Windows 下 cmd.exe /c）执行命令。
 * <p>stdout/stderr 各限 32 KiB，按动作超时终止进程树；非零退出码与启动失败返回错误文本。</p>
 */
public final class CommandExecutor implements HookActionExecutor {

    private static final int MAX_OUTPUT_BYTES = 32 * 1024;
    private static final long POLL_MILLIS = 50;

    @Override
    public String execute(Action action, HookContext context) {
        if (!(action instanceof CommandAction cmd)) {
            throw new IllegalArgumentException("CommandExecutor 只接受 command 动作");
        }
        String command = VariableResolver.resolve(cmd.command(), context);
        ProcessBuilder pb = new ProcessBuilder(shellCommand(command));
        try {
            Process process = pb.start();
            StreamGobbler out = new StreamGobbler(process.getInputStream());
            StreamGobbler err = new StreamGobbler(process.getErrorStream());
            Thread outThread = new Thread(out, "hook-cmd-stdout");
            outThread.setDaemon(true);
            outThread.start();
            Thread errThread = new Thread(err, "hook-cmd-stderr");
            errThread.setDaemon(true);
            errThread.start();

            long deadline = System.nanoTime() + cmd.timeout().toNanos();
            boolean finished;
            try {
                while (!(finished = process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS))) {
                    if (System.nanoTime() >= deadline) {
                        killTree(process);
                        return "命令执行超时（" + cmd.timeout().toSeconds() + " 秒）";
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                killTree(process);
                return "命令被中断";
            }
            try {
                outThread.join(2000);
                errThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            String stdout = out.getOutput();
            String stderr = err.getOutput();
            StringBuilder sb = new StringBuilder();
            if (!stdout.isEmpty()) {
                sb.append(stdout);
            }
            if (!stderr.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("stderr:\n").append(stderr);
            }
            int exit = process.exitValue();
            if (exit != 0) {
                String msg = "命令退出码: " + exit;
                if (sb.length() > 0) msg += "\n" + sb;
                return msg;
            }
            return sb.toString();
        } catch (IOException e) {
            return "启动命令失败: " + e.getMessage();
        }
    }

    private static String[] shellCommand(String command) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("windows")) {
            return new String[]{"cmd.exe", "/c", command};
        }
        return new String[]{"/bin/sh", "-c", command};
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

    /** 流读取器：独立线程读取并限制为 32 KiB。 */
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
                int read;
                while ((read = input.read(buf)) != -1) {
                    if (overLimit) continue;
                    if (buffer.size() + read > MAX_OUTPUT_BYTES) {
                        int allowed = MAX_OUTPUT_BYTES - buffer.size();
                        if (allowed > 0) buffer.write(buf, 0, allowed);
                        overLimit = true;
                    } else {
                        buffer.write(buf, 0, read);
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
