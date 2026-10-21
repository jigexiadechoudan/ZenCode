package dev.zencode.tool.impl;

import dev.zencode.tool.*;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * 对 UTF-8 文本文件执行精确的唯一文本替换。
 * <p>
 * 旧文本必须恰好出现一次；不匹配或多次匹配时不写入文件并返回结构化失败结果。
 * 编辑采用原子替换（同目录临时文件 + 重命名），并保持原文件 LF 或 CRLF 换行风格。
 * </p>
 */
public final class EditFileTool extends BaseTool {
    private static final String PARAM_PATH = "path";
    private static final String PARAM_OLD_TEXT = "old_text";
    private static final String PARAM_NEW_TEXT = "new_text";

    private final WorkspacePathResolver pathResolver;

    public EditFileTool(WorkspacePathResolver pathResolver) {
        super("EditFile",
                "对工作区内的 UTF-8 文本文件执行精确的唯一文本替换，是修改既有文件的首选工具。"
                        + "旧文本必须且只能出现一次；不匹配或多次匹配都不写入。修改前必须先 ReadFile 读取现状，"
                        + "不要用 WriteFile 做局部修改。原子替换并保持原换行风格。",
                ToolCategory.WRITE, ToolPermission.REQUIRES_CONFIRMATION,
                jsonSchema(Map.of(
                        PARAM_PATH, stringProp("文件路径（相对或工作区内绝对路径）"),
                        PARAM_OLD_TEXT, stringProp("要替换的旧文本（必须恰好出现一次）"),
                        PARAM_NEW_TEXT, stringProp("替换后的新文本")
                ), List.of(PARAM_PATH, PARAM_OLD_TEXT, PARAM_NEW_TEXT)));
        this.pathResolver = pathResolver;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String userPath = requiredString(args, PARAM_PATH);
        String oldText = requiredString(args, PARAM_OLD_TEXT);
        String newText = requiredString(args, PARAM_NEW_TEXT);

        Path target;
        try {
            target = pathResolver.resolve(userPath);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(ctx.callId(), name(), e.getMessage());
        }

        // 检查文件存在
        if (!Files.exists(target)) {
            return ToolResult.error(ctx.callId(), name(), "文件不存在: " + pathResolver.relativize(target));
        }

        // 检查是否为目录
        if (Files.isDirectory(target)) {
            return ToolResult.error(ctx.callId(), name(), "路径是目录，不是文件: " + pathResolver.relativize(target));
        }

        // 读取文件内容
        String content;
        try {
            content = Files.readString(target, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return ToolResult.error(ctx.callId(), name(), "文件不是有效的 UTF-8 编码: " + pathResolver.relativize(target));
        } catch (IOException e) {
            return ToolResult.error(ctx.callId(), name(), "读取文件失败: " + e.getMessage());
        }

        // 统计非重叠出现次数
        int count = countOccurrences(content, oldText);
        if (count == 0) {
            return ToolResult.error(ctx.callId(), name(), "未找到匹配的旧文本 '" + abbreviate(oldText, 50) + "'");
        }
        if (count > 1) {
            return ToolResult.error(ctx.callId(), name(), "旧文本出现 " + count + " 次，需要恰好一次");
        }

        // 检测换行风格并保持
        String lineEnding = detectLineEnding(target);

        // 先统一为 LF，再替换，最后转换到目标换行风格
        String normalizedContent = content.replace("\r\n", "\n").replace("\r", "\n");
        String newContent = normalizedContent.replace(oldText, newText);
        if ("\r\n".equals(lineEnding)) {
            newContent = newContent.replace("\n", "\r\n");
        }

        // 原子写入
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile(target.getParent(), ".zencode-edit-", ".tmp");
            Files.writeString(tempFile, newContent, StandardCharsets.UTF_8);
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return ToolResult.success(ctx.callId(), name(), "文件已编辑: " + pathResolver.relativize(target)
                    + "（替换 1 处）");
        } catch (IOException e) {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
            return ToolResult.error(ctx.callId(), name(), "写入文件失败: " + e.getMessage());
        }
    }

    /** 统计非重叠出现次数。包级公开供测试使用。 */
    public static int countOccurrences(String text, String pattern) {
        if (pattern == null || pattern.isEmpty()) return 0;
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }

    /** 检测文件换行风格。 */
    private static String detectLineEnding(Path path) {
        if (!Files.exists(path)) return "\n";
        try {
            byte[] bytes = Files.readAllBytes(path);
            for (int i = 0; i < bytes.length - 1; i++) {
                if (bytes[i] == '\r' && bytes[i + 1] == '\n') return "\r\n";
            }
        } catch (IOException ignored) {}
        return "\n";
    }

    /** 截断长字符串用于错误消息。 */
    private static String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}