package dev.zencode.tool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具输出限制器，统一限制执行结果为 32 KiB。
 * <p>
 * 按完整行或完整路径项优先保留，截断后设置截断标记和稳定提示。
 * 文件读取、Glob、Grep 和 Bash 使用同一限制逻辑。
 * </p>
 */
public final class ToolOutputLimiter {
    /** 最大输出字节数（32 KiB）。 */
    public static final int MAX_OUTPUT_BYTES = 32 * 1024;

    private ToolOutputLimiter() {}

    /**
     * 限制文本内容为 32 KiB，按完整行优先保留。
     *
     * @param content 原始文本内容
     * @return 限制结果，包含截断后的文本和是否截断标记
     */
    public static LimitResult limitText(String content) {
        if (content == null || content.isEmpty()) {
            return new LimitResult("", false);
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_OUTPUT_BYTES) {
            return new LimitResult(content, false);
        }

        // 按行截断，保留完整行
        String[] lines = content.split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String candidate = (i == 0 ? "" : "\n") + lines[i];
            byte[] candidateBytes = (result.toString() + candidate).getBytes(StandardCharsets.UTF_8);
            if (candidateBytes.length > MAX_OUTPUT_BYTES) {
                result.append("\n...(输出截断，剩余 ").append(lines.length - i).append(" 行被省略)");
                return new LimitResult(result.toString(), true);
            }
            if (result.length() > 0) {
                result.append("\n");
            }
            result.append(lines[i]);
        }
        // 理论上不会到达这里，因为前面已检查过
        return new LimitResult(result.toString(), false);
    }

    /**
     * 限制路径列表为 32 KiB，按完整路径项优先保留。
     *
     * @param paths 路径列表
     * @return 限制结果
     */
    public static LimitResult limitPaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return new LimitResult("", false);
        }
        StringBuilder result = new StringBuilder();
        int truncatedCount = 0;
        for (String path : paths) {
            String candidate = result.length() > 0 ? "\n" + path : path;
            byte[] candidateBytes = (result.toString() + candidate).getBytes(StandardCharsets.UTF_8);
            if (candidateBytes.length > MAX_OUTPUT_BYTES) {
                truncatedCount = paths.size() - paths.indexOf(path);
                break;
            }
            if (result.length() > 0) result.append("\n");
            result.append(path);
        }
        if (truncatedCount > 0) {
            result.append("\n...(输出截断，剩余 ").append(truncatedCount).append(" 项被省略)");
            return new LimitResult(result.toString(), true);
        }
        return new LimitResult(result.toString(), false);
    }

    /**
     * 限制 Bash 输出文本为 32 KiB，按完整行优先保留。
     * 适用于 stdout 和 stderr 分别限制。
     */
    public static LimitResult limitBashOutput(String output) {
        if (output == null || output.isEmpty()) {
            return new LimitResult("", false);
        }
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_OUTPUT_BYTES) {
            return new LimitResult(output, false);
        }
        String[] lines = output.split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String candidate = result.length() > 0 ? "\n" + lines[i] : lines[i];
            byte[] candidateBytes = (result + candidate).getBytes(StandardCharsets.UTF_8);
            if (candidateBytes.length > MAX_OUTPUT_BYTES) {
                result.append("\n...(输出截断)");
                return new LimitResult(result.toString(), true);
            }
            if (result.length() > 0) result.append("\n");
            result.append(lines[i]);
        }
        return new LimitResult(result.toString(), false);
    }

    /** 限制结果。 */
    public record LimitResult(String text, boolean truncated) {
        public LimitResult {
            if (text == null) text = "";
        }
    }
}