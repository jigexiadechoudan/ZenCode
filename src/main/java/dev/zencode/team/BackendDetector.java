package dev.zencode.team;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * 执行后端环境检测：AUTO 按 tmux → iTerm2 → in-process 优先级选择；
 * 显式指定但不可用抛 {@link TeamException}（不静默降级）。
 * <p>
 * 探针可注入（测试用），默认真实探测：tmux 查 PATH 可执行文件；
 * iTerm2 查 macOS + iTerm.app 存在。
 * </p>
 */
public final class BackendDetector {

    private final BooleanSupplier tmuxProbe;
    private final BooleanSupplier iterm2Probe;

    public BackendDetector() {
        this(BackendDetector::tmuxAvailable, BackendDetector::iterm2Available);
    }

    BackendDetector(BooleanSupplier tmuxProbe, BooleanSupplier iterm2Probe) {
        this.tmuxProbe = tmuxProbe;
        this.iterm2Probe = iterm2Probe;
    }

    /**
     * 解析请求的后端：AUTO 按优先级落地；显式不可用抛可读错误。
     *
     * @return 落地后的具体后端类型
     */
    public BackendType detect(BackendType requested) {
        if (requested == null) throw new TeamException("后端类型不能为空");
        return switch (requested) {
            case TMUX -> {
                if (tmuxProbe.getAsBoolean()) {
                    yield BackendType.TMUX;
                }
                throw new TeamException("后端 TMUX 在当前环境不可用: " + describeTmux());
            }
            case ITERM2 -> {
                if (iterm2Probe.getAsBoolean()) {
                    yield BackendType.ITERM2;
                }
                throw new TeamException("后端 ITERM2 在当前环境不可用: " + describeIterm2());
            }
            case INPROCESS -> BackendType.INPROCESS;
            case AUTO -> {
                if (tmuxProbe.getAsBoolean()) {
                    yield BackendType.TMUX;
                }
                if (iterm2Probe.getAsBoolean()) {
                    yield BackendType.ITERM2;
                }
                yield BackendType.INPROCESS;
            }
        };
    }

    /** 检测结果可读说明（记录到队员元信息与日志）。 */
    public String describe(BackendType requested) {
        return switch (requested) {
            case TMUX -> describeTmux();
            case ITERM2 -> describeIterm2();
            case INPROCESS -> "同进程虚拟线程运行，始终可用";
            case AUTO -> "自动检测: " + describeTmux() + "；" + describeIterm2() + "；" + describeInprocess();
        };
    }

    private String describeTmux() {
        return tmuxProbe.getAsBoolean() ? "tmux 可用" : "未检测到 tmux 可执行文件（PATH 查找）";
    }

    private String describeIterm2() {
        return iterm2Probe.getAsBoolean() ? "iTerm2 可用（macOS）" : "iTerm2 不可用（仅 macOS 且需安装 iTerm.app）";
    }

    private static String describeInprocess() {
        return "in-process 始终可用";
    }

    /** 真实探测：PATH 中是否存在 tmux / tmux.exe。 */
    public static boolean tmuxAvailable() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path p = Path.of(dir);
            if (Files.isExecutable(p.resolve("tmux")) || Files.isExecutable(p.resolve("tmux.exe"))) {
                return true;
            }
        }
        return false;
    }

    /** 真实探测：macOS 且 iTerm2 应用存在。 */
    public static boolean iterm2Available() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("mac") && Files.isDirectory(Path.of("/Applications/iTerm.app"));
    }
}
