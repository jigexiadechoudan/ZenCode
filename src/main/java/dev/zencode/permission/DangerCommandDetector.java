package dev.zencode.permission;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 危险命令检测器：通过正则黑名单识别不可逆的系统破坏命令。
 * <p>
 * 针对 Windows shell（cmd / PowerShell），覆盖删除（del / rd / rmdir / format）、
 * 关机重启（shutdown）、强制杀进程（taskkill）、注册表删除（reg delete）、
 * 磁盘管理（diskpart）、权限夺取（takeown / icacls 破坏性用法）、
 * 远程脚本下载即执行（iwr | iex、irm | iex）等。
 * 命中即硬拦截，不触发 HITL。
 * </p>
 * <p>
 * 匹配对完整命令文本做大小写不敏感正则测试。
 * </p>
 */
public final class DangerCommandDetector {

    /**
     * 危险命令正则集。每条为完整命令的子串/整句匹配，大小写不敏感。
     */
    private static final List<String> BLOCKED_PATTERNS = List.of(
            // 递归删除文件/目录（不可逆）
            "\\bdel\\s+[^&|]*?/s\\b",
            "\\bdel\\s+[^&|]*?/q\\b",
            "\\b(?:rd|rmdir)\\s+[^&|]*?/s\\b",
            "\\b(?:rd|rmdir)\\s+[^&|]*?/q\\b",
            "remove-item\\s+[^&|]*-recurse",
            "remove-item\\s+[^&|]*-recursive",
            // 格式化 / 磁盘管理
            "\\bformat\\s+[a-z]:",
            "\\bdiskpart\\b",
            // 关机 / 重启 / 注销
            "\\bshutdown\\s+[^&|]*(?:/r|/s)\\b",
            // 强制杀进程
            "\\btaskkill\\s+[^&|]*/f\\b",
            "\\bstop-process\\s+[^&|]*-?f",
            // 注册表删除 / 破坏性操作
            "\\breg\\s+delete\\b",
            // 权限夺取 / 所有权
            "\\btakeown\\s+/f\\b",
            "\\bicacls\\s+[^&|]*/reset\\b",
            // 远程脚本下载即执行（PowerShell：iwr/irm ... | iex）
            "\\|[^|]*\\b(?:iex|invoke-expression)\\b",
            "(?:\\.net\\.webclient|\\.download\\.string)\\s*\\("
    );

    private static final List<Pattern> PATTERNS =
            BLOCKED_PATTERNS.stream().map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                    .toList();

    /** 给定完整命令文本，是否命中任一危险模式。 */
    public boolean matches(String command) {
        if (command == null || command.isBlank()) return false;
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(command).find()) return true;
        }
        return false;
    }

    /** 内置危险正则源串，供测试与展示。 */
    public List<String> blockedPatterns() {
        return BLOCKED_PATTERNS;
    }
}