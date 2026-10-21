package dev.zencode.context;

import dev.zencode.config.ContextConfig;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolResultContent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 第一层预防：大工具结果存盘（F2）。
 * <p>
 * 单条结果内容超过阈值时完整写入工作区 {@code .zencode/context/}，
 * 对话只保留预览 + 路径；同一轮所有结果合计超过阈值时按内容大小
 * 从大到小依次存盘，直到合计降到阈值内。存盘只作用于工具结果，
 * 用户消息与助手文本绝不改写。存盘失败降级为原文，不阻断。
 * </p>
 */
public final class ToolResultSpooler {

    /** 预览保留的最大行数。 */
    private static final int PREVIEW_LINES = 5;

    private Path workingDirectory;
    private final ContextConfig config;
    private Path contextDir;
    private volatile String sessionId = UUID.randomUUID().toString();

    public ToolResultSpooler(Path workingDirectory, ContextConfig config) {
        this.workingDirectory = workingDirectory;
        this.config = config;
    }

    public void setSessionId(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) this.sessionId = safeName(sessionId);
    }

    public void refreshForNewDirectory(Path directory) {
        workingDirectory = directory;
        contextDir = null;
    }

    /** 工作区 {@code .zencode/context/} 目录；创建失败时降级为工作区目录。 */
    public Path contextDir() {
        if (contextDir != null) {
            return contextDir;
        }
        Path candidate = workingDirectory.resolve(".zencode").resolve("context");
        try {
            Files.createDirectories(candidate);
            contextDir = candidate;
        } catch (IOException e) {
            contextDir = workingDirectory;
        }
        return contextDir;
    }

    /**
     * 处理单轮工具结果：超阈值结果写盘并替换为预览。
     *
     * @param roundResults 该轮全部工具结果（保持原有顺序）
     * @return 与输入同序的处理结果列表
     */
    public List<SpooledResult> spoolRound(List<ToolResultContent> roundResults) {
        return spoolRound(roundResults, 0);
    }

    /** roundIndex 为持久会话中的轮次下标；文件名同时包含会话、轮次与随机后缀。 */
    public List<SpooledResult> spoolRound(List<ToolResultContent> roundResults, int roundIndex) {
        if (roundResults == null || roundResults.isEmpty()) {
            return List.of();
        }
        List<SpooledResult> results = new ArrayList<>(roundResults.size());
        for (ToolResultContent content : roundResults) {
            results.add(new SpooledResult(content, false));
        }

        // 第一遍：单条超阈值直接写盘
        for (int i = 0; i < results.size(); i++) {
            ToolResultContent content = results.get(i).content();
            if (byteLength(content) > config.singleResultThresholdBytes()) {
                results.set(i, spoolOne(content, roundIndex, i));
            }
        }

        // 第二遍：未写盘的合计仍超阈值 → 按大小从大到小依次写盘，直到合计达标
        long remainingTotal = results.stream()
                .filter(r -> !r.spooled())
                .mapToLong(r -> byteLength(r.content()))
                .sum();
        if (remainingTotal > config.roundTotalThresholdBytes()) {
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                if (!results.get(i).spooled()) {
                    indices.add(i);
                }
            }
            indices.sort(Comparator.comparingLong((Integer i) -> byteLength(results.get(i).content())).reversed());
            for (int index : indices) {
                if (remainingTotal <= config.roundTotalThresholdBytes()) {
                    break;
                }
                remainingTotal -= byteLength(results.get(index).content());
                results.set(index, spoolOne(results.get(index).content(), roundIndex, index));
            }
        }
        return List.copyOf(results);
    }

    /** 写盘并返回替换为预览的结果。写盘失败时原样返回（不写盘标记）。 */
    private SpooledResult spoolOne(ToolResultContent content, int roundIndex, int fallbackIndex) {
        String original = content.result().content();
        String base = fileNameFor(content.callId(), fallbackIndex).replace(".txt", "");
        Path target = contextDir().resolve("tool-" + sessionId + "-r" + roundIndex
                + "-" + base.substring(5) + "-" + UUID.randomUUID() + ".txt");
        try {
            Files.writeString(target, original, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            return new SpooledResult(content, false);
        }
        String preview = buildPreview(original, target);
        ToolResult previewResult = ToolResult.success(content.callId(), content.toolName(), preview);
        return new SpooledResult(
                new ToolResultContent(previewResult, content.callId(), content.toolName()), true);
    }

    /** 构造「预览 + 路径提示」，预览 UTF-8 字节不超过单条阈值。 */
    private String buildPreview(String original, Path target) {
        StringBuilder preview = new StringBuilder();
        int limit = Math.min(configThresholdForPreview(), Math.max(1, original.length()));
        String[] lines = original.split("\n", -1);
        int included = 0;
        int index = 0;
        while (index < lines.length && byteLength(preview.toString() + lines[index]) <= limit) {
            if (preview.length() > 0) {
                preview.append("\n");
            }
            preview.append(lines[index]);
            included++;
            index++;
        }
        preview.append("\n...(完整内容已存至 ").append(target).append("，可用 ReadFile 读取)");
        return preview.toString();
    }

    /** 预览字节上限：与单条阈值一致，但至少保留少量内容。 */
    private int configThresholdForPreview() {
        return Math.max(config.singleResultThresholdBytes(), 64);
    }

    /** 生成安全文件名：callId 清洗为 [A-Za-z0-9_-]，空或全非法时用序号。 */
    static String fileNameFor(String callId, int fallbackIndex) {
        String cleaned = safeName(callId);
        if (cleaned.isEmpty()) {
            cleaned = "r" + fallbackIndex;
        }
        return "tool-" + cleaned + ".txt";
    }

    private static String safeName(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "");
    }

    private static int byteLength(ToolResultContent content) {
        return byteLength(content.result().content());
    }

    private static int byteLength(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 单条处理结果：content 已替换为预览时 spooled=true。 */
    public record SpooledResult(ToolResultContent content, boolean spooled) {}
}
