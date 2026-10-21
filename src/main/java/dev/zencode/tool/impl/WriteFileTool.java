package dev.zencode.tool.impl;

import dev.zencode.tool.*;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

/**
 * 写入 UTF-8 文本文件，允许覆盖已有文件，并在需要时创建父目录。
 * 写入采用原子替换（同目录临时文件 + 重命名），保持原文件换行风格。
 */
public final class WriteFileTool extends BaseTool {
    private static final String PARAM_PATH = "path";
    private static final String PARAM_CONTENT = "content";

    private final WorkspacePathResolver pathResolver;

    public WriteFileTool(WorkspacePathResolver pathResolver) {
        super("WriteFile",
                "写入 UTF-8 文本文件到工作区，用于新建文件或确有必要的整体重写。允许覆盖已有文件并自动创建父目录，"
                        + "采用原子替换。不用于既有文件的局部修改——那应先用 ReadFile 读取、再用 EditFile 精确替换。",
                ToolCategory.WRITE, ToolPermission.REQUIRES_CONFIRMATION,
                jsonSchema(Map.of(
                        PARAM_PATH, stringProp("文件路径（相对或工作区内绝对路径）"),
                        PARAM_CONTENT, stringProp("要写入的文本内容")
                ), List.of(PARAM_PATH, PARAM_CONTENT)));
        this.pathResolver = pathResolver;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String userPath = requiredString(args, PARAM_PATH);
        String content = requiredString(args, PARAM_CONTENT);

        Path target;
        try {
            target = pathResolver.resolve(userPath);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(ctx.callId(), name(), e.getMessage());
        }

        // 创建父目录
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            return ToolResult.error(ctx.callId(), name(), "创建父目录失败: " + e.getMessage());
        }

        // 检测现有文件的换行风格
        String lineEnding = detectLineEnding(target);

        // 规范化换行符
        String normalizedContent = normalizeLineEndings(content, lineEnding);

        // 原子写入：先写入同目录临时文件，再重命名替换
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile(target.getParent(), ".zencode-write-", ".tmp");
            Files.writeString(tempFile, normalizedContent, StandardCharsets.UTF_8);
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return ToolResult.success(ctx.callId(), name(), "文件已写入: " + pathResolver.relativize(target)
                    + " (" + normalizedContent.length() + " 字符)");
        } catch (IOException e) {
            // 清理临时文件
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
            return ToolResult.error(ctx.callId(), name(), "写入文件失败: " + e.getMessage());
        }
    }

    /**
     * 检测文件的换行风格。优先使用 LF；如果文件已有 CRLF 则保持 CRLF。
     */
    private static String detectLineEnding(Path path) {
        if (!Files.exists(path)) return "\n"; // 新文件默认 LF
        try {
            byte[] bytes = Files.readAllBytes(path);
            for (int i = 0; i < bytes.length - 1; i++) {
                if (bytes[i] == '\r' && bytes[i + 1] == '\n') return "\r\n";
            }
        } catch (IOException ignored) {}
        return "\n";
    }

    /**
     * 将文本中的换行符统一为指定格式。
     */
    private static String normalizeLineEndings(String text, String lineEnding) {
        if (text == null || text.isEmpty()) return text;
        // 先将所有换行转为 LF，再按目标风格替换
        String lf = text.replace("\r\n", "\n").replace("\r", "\n");
        if ("\r\n".equals(lineEnding)) {
            return lf.replace("\n", "\r\n");
        }
        return lf;
    }
}