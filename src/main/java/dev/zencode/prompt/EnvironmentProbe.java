package dev.zencode.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 环境快照探测（F3、N6、N9）。
 * <p>
 * 每个会话仅调用一次；只读取元数据字段（工作目录、Windows 版本、CPU 架构、Shell、
 * 当前日期、模型名与 Git 状态），不读取 API Key、环境变量密钥或文件正文（N5）。
 * Git 不可用或当前目录不是仓库时仅将对应字段降级，不抛出异常（N6）。
 * </p>
 */
public final class EnvironmentProbe {

    private EnvironmentProbe() {}

    /** 探测一次环境快照。 */
    public static EnvironmentSnapshot probe(Path workingDirectory, String modelName) {
        String workingDir = safeString(workingDirectory == null
                ? null : workingDirectory.toAbsolutePath().toString());
        String windowsVersion = System.getProperty("os.version") == null ? "未知"
                : System.getProperty("os.version");
        String cpuArch = System.getProperty("os.arch") == null ? "未知"
                : System.getProperty("os.arch");
        String shell = detectShell();
        String currentDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        boolean inGitTree = false;
        String gitBranch = "未知";
        boolean hasUncommitted = false;
        try {
            inGitTree = isGitRepository(workingDirectory);
            if (inGitTree) {
                gitBranch = gitBranch(workingDirectory);
                hasUncommitted = hasUncommittedChanges(workingDirectory);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inGitTree = false;
            gitBranch = "未知";
            hasUncommitted = false;
        } catch (RuntimeException | IOException ignored) {
            // Git 不可用仅降级，不阻塞会话启动
            inGitTree = false;
            gitBranch = "未知";
            hasUncommitted = false;
        }

        return new EnvironmentSnapshot(workingDir, windowsVersion, cpuArch, shell,
                currentDate, safeString(modelName), inGitTree, gitBranch, hasUncommitted);
    }

    private static String detectShell() {
        String comspec = System.getenv("COMSPEC");
        return comspec == null || comspec.isBlank() ? "未知" : comspec;
    }

    private static boolean isGitRepository(Path dir) throws IOException, InterruptedException {
        ProcessResult r = execute(dir, "git", "rev-parse", "--is-inside-work-tree");
        return r.exitCode() == 0 && r.stdout().trim().equals("true");
    }

    private static String gitBranch(Path dir) throws IOException, InterruptedException {
        ProcessResult r = execute(dir, "git", "branch", "--show-current");
        String out = r.stdout().trim();
        return out.isEmpty() ? "未知" : out;
    }

    private static boolean hasUncommittedChanges(Path dir) throws IOException, InterruptedException {
        ProcessResult r = execute(dir, "git", "status", "--porcelain");
        return r.exitCode() == 0 && !r.stdout().trim().isEmpty();
    }

    private static ProcessResult execute(Path dir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dir != null && Files.isDirectory(dir)) pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return new ProcessResult(p.exitValue(), out);
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private record ProcessResult(int exitCode, String stdout) {}
}