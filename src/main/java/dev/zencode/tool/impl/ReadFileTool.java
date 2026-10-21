package dev.zencode.tool.impl;

import dev.zencode.tool.*;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 读取 UTF-8 文本文件，支持可选起始行和行数，返回内容包含文件路径和原始行号。
 */
public final class ReadFileTool extends BaseTool {
    private static final String PARAM_PATH = "path";
    private static final String PARAM_START_LINE = "start_line";
    private static final String PARAM_END_LINE = "end_line";

    private final WorkspacePathResolver pathResolver;

    public ReadFileTool(WorkspacePathResolver pathResolver) {
        super("ReadFile",
                "读取工作区内的 UTF-8 文本文件，是查看现有文件内容的首选工具。"
                        + "优先用于读取源文件、配置等实际内容；不适用于列出目录（用 Glob）或搜索内容（用 Grep），"
                        + "也绝不应用 Bash 模拟读取。修改文件前应先 ReadFile 确认现状。支持起始行与行数，返回带行号内容。",
                ToolCategory.READ, ToolPermission.READ_ONLY,
                jsonSchema(Map.of(
                        PARAM_PATH, stringProp("文件路径（相对或工作区内绝对路径）"),
                        PARAM_START_LINE, integerProp("起始行号（从 1 开始，可选）"),
                        PARAM_END_LINE, integerProp("结束行号（可选，包含）")
                ), List.of(PARAM_PATH)));
        this.pathResolver = pathResolver;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String userPath = requiredString(args, PARAM_PATH);
        int startLine = optionalInt(args, PARAM_START_LINE, 1);
        int endLine = optionalInt(args, PARAM_END_LINE, Integer.MAX_VALUE);

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
        List<String> lines;
        try {
            lines = Files.readAllLines(target, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return ToolResult.error(ctx.callId(), name(), "文件不是有效的 UTF-8 编码: " + pathResolver.relativize(target));
        } catch (IOException e) {
            return ToolResult.error(ctx.callId(), name(), "读取文件失败: " + e.getMessage());
        }

        // 校验行范围
        if (startLine < 1) startLine = 1;
        if (endLine < startLine) {
            return ToolResult.error(ctx.callId(), name(), "结束行号不能小于起始行号");
        }
        if (startLine > lines.size()) {
            return ToolResult.error(ctx.callId(), name(), "起始行号超出文件总行数 (" + lines.size() + " 行)");
        }

        // 截取行范围
        int toIndex = Math.min(endLine, lines.size());
        List<String> selectedLines = lines.subList(startLine - 1, toIndex);

        // 构建输出（含行号）
        StringBuilder content = new StringBuilder();
        String relativePath = pathResolver.relativize(target);
        content.append("文件: ").append(relativePath).append("\n");
        if (selectedLines.size() != lines.size()) {
            content.append("行: ").append(startLine).append(" - ").append(toIndex).append(" / 共 ").append(lines.size()).append(" 行\n");
        } else {
            content.append("共 ").append(lines.size()).append(" 行\n");
        }
        content.append("────\n");

        for (int i = 0; i < selectedLines.size(); i++) {
            content.append(String.format("%6d  %s%n", startLine + i, selectedLines.get(i)));
        }

        // 应用输出限制
        ToolOutputLimiter.LimitResult limited = ToolOutputLimiter.limitText(content.toString());
        if (limited.truncated()) {
            return ToolResult.truncated(ctx.callId(), name(), limited.text());
        }
        return ToolResult.success(ctx.callId(), name(), content.toString());
    }
}