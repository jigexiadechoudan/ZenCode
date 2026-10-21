package dev.zencode.mcp.transport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * stdio 传输：启动配置命令的子进程，向 stdin 写入 JSON 行，从 stdout 按行读取 JSON 消息。
 * <p>
 * 环境变量隔离：子进程只继承宿主 {@code PATH} 与该传输显式声明的变量，
 * 其余宿主环境变量一律不传递，防止敏感信息泄露给外部 Server。
 * stderr 内容仅记录到日志，不参与协议。
 * </p>
 */
public final class StdioTransport implements Transport {
    private final Process process;
    private final BufferedWriter stdin;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Consumer<String> handler;

    public StdioTransport(String command, List<String> args, Map<String, String> extraEnv) throws IOException {
        Objects.requireNonNull(command, "命令不能为空");
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        if (args != null) {
            cmd.addAll(args);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        configureIsolatedEnvironment(pb, extraEnv);
        pb.redirectErrorStream(false);
        this.process = pb.start();
        this.stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        Thread reader = new Thread(this::readLoop, "mcp-stdio-read-" + command);
        reader.setDaemon(true);
        reader.start();
        Thread stderr = new Thread(this::drainStderr, "mcp-stdio-err-" + command);
        stderr.setDaemon(true);
        stderr.start();
    }

    /** 环境变量隔离：清空默认环境，只保留宿主 PATH 与显式声明的变量。 */
    private static void configureIsolatedEnvironment(ProcessBuilder pb, Map<String, String> extraEnv) {
        Map<String, String> env = pb.environment();
        env.clear();
        String path = findHostPath();
        if (path != null) {
            env.put("PATH", path);
        }
        if (extraEnv != null) {
            env.putAll(extraEnv);
        }
    }

    /** 从宿主环境变量中按名称（忽略大小写）查找 PATH，Windows 下键名可能是 Path。 */
    private static String findHostPath() {
        Map<String, String> hostEnv = System.getenv();
        for (Map.Entry<String, String> entry : hostEnv.entrySet()) {
            if ("PATH".equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** stdout 读线程：逐行回调入站消息。 */
    private void readLoop() {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                Consumer<String> h = handler;
                if (h != null) {
                    h.accept(line);
                }
            }
        } catch (IOException ignored) {
            // 进程退出或连接关闭，读线程结束
        }
    }

    /** stderr drain 线程：仅记录日志，不参与协议。 */
    private void drainStderr() {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                System.err.println("[mcp-stdio] " + line);
            }
        } catch (IOException ignored) {
            // 进程退出时忽略
        }
    }

    @Override
    public void send(String message) {
        if (closed.get()) {
            throw new TransportException("传输已关闭");
        }
        try {
            stdin.write(message);
            stdin.write("\n");
            stdin.flush();
        } catch (IOException e) {
            throw new TransportException("向子进程写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void setMessageHandler(Consumer<String> handler) {
        this.handler = Objects.requireNonNull(handler, "消息处理器不能为空");
    }

    @Override
    public boolean isAlive() {
        return !closed.get() && process.isAlive();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            stdin.close();
        } catch (IOException ignored) {
            // 忽略
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
