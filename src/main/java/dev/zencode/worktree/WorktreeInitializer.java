package dev.zencode.worktree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * worktree 创建后环境初始化：复制本地配置、配置 git hooks、junction 依赖目录、
 * 复制被 gitignore 但运行需要的文件。全部 best-effort，失败记日志不阻断。
 */
public final class WorktreeInitializer {

    private static final System.Logger LOG = System.getLogger("dev.zencode.worktree.WorktreeInitializer");

    private WorktreeInitializer() {}

    /**
     * @param repoRoot     主仓库根目录
     * @param worktreePath worktree 目录（已创建）
     * @param config       worktree 配置
     */
    public static void initialize(Path repoRoot, Path worktreePath, WorktreeConfig config) {
        copyLocalFiles(repoRoot, worktreePath, config.copyLocalFiles());
        configureHooks(repoRoot, worktreePath);
        linkDirs(repoRoot, worktreePath, config.linkDirs());
        copyIgnoredFiles(repoRoot, worktreePath, config.copyIgnored());
    }

    /** 复制本地配置文件名（如 settings.local.json）。 */
    private static void copyLocalFiles(Path repoRoot, Path worktreePath, List<String> names) {
        for (String name : names) {
            Path source = repoRoot.resolve(name);
            Path target = worktreePath.resolve(name);
            if (!Files.isRegularFile(source)) continue;
            try {
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "复制本地配置失败: " + name + " - " + e.getMessage());
            }
        }
    }

    /** 按主仓库 core.hooksPath 配置 worktree 的 git hooks。 */
    private static void configureHooks(Path repoRoot, Path worktreePath) {
        GitCommand.GitResult r = GitCommand.run(repoRoot, "config", "--get", "core.hooksPath");
        if (!r.ok() || r.stdout().isBlank()) return;
        String hooksPath = r.stdout().trim();
        GitCommand.run(worktreePath, "config", "core.hooksPath", hooksPath);
    }

    /** 对依赖目录建立 junction（mklink /J），失败跳过记日志。 */
    private static void linkDirs(Path repoRoot, Path worktreePath, List<String> dirs) {
        for (String dir : dirs) {
            Path source = repoRoot.resolve(dir);
            Path target = worktreePath.resolve(dir);
            if (!Files.isDirectory(source)) {
                LOG.log(System.Logger.Level.WARNING, "junction 源目录不存在，跳过: " + source);
                continue;
            }
            if (Files.exists(target)) continue;
            try {
                ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c",
                        "mklink", "/J", target.toString(), source.toString());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.getInputStream().readAllBytes();
                int exit = p.waitFor();
                if (exit != 0) {
                    LOG.log(System.Logger.Level.WARNING, "junction 建立失败: " + dir);
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.log(System.Logger.Level.WARNING, "junction 建立异常: " + dir + " - " + e.getMessage());
            }
        }
    }

    /** 复制被 gitignore 但运行需要的文件（glob，best-effort）。 */
    private static void copyIgnoredFiles(Path repoRoot, Path worktreePath, List<String> globs) {
        for (String glob : globs) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            try (var stream = Files.walk(repoRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> !p.startsWith(repoRoot.resolve(".git")))
                        .filter(p -> !p.startsWith(repoRoot.resolve(".zencode")))
                        .filter(p -> matcher.matches(repoRoot.relativize(p)))
                        .forEach(p -> {
                            Path target = worktreePath.resolve(repoRoot.relativize(p));
                            try {
                                Files.createDirectories(target.getParent());
                                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException e) {
                                LOG.log(System.Logger.Level.WARNING,
                                        "复制忽略文件失败: " + p + " - " + e.getMessage());
                            }
                        });
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "扫描忽略文件失败: " + e.getMessage());
            }
        }
    }

    /** 读取 worktree HEAD（快速恢复用）。 */
    public static String readHead(Path worktreePath) {
        try {
            Path gitFile = worktreePath.resolve(".git");
            String head = Files.readString(gitFile, StandardCharsets.UTF_8).trim();
            // .git 文件内容形如 "gitdir: /path/to/.git/worktrees/<name>"
            return head.startsWith("gitdir: ") ? head.substring("gitdir: ".length()) : head;
        } catch (IOException e) {
            return "";
        }
    }
}
