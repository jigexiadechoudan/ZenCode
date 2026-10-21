package dev.zencode.tool.impl;

import dev.zencode.tool.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 在工作区内递归匹配通配符和 ** 模式，返回相对于工作目录的匹配路径列表，并按稳定顺序排序。
 * <p>
 * 结果最多返回 32 KiB，超出时按路径顺序返回完整路径项并标记截断。
 * 解析并校验符号链接目标，跳过越界链接并避免符号链接循环。
 * </p>
 */
public final class GlobTool extends BaseTool {
    private static final String PARAM_PATTERN = "pattern";

    private final WorkspacePathResolver pathResolver;

    public GlobTool(WorkspacePathResolver pathResolver) {
        super("Glob",
                "在工作区内递归匹配文件路径，支持通配符与 ** 模式，返回排序后的相对路径列表，"
                        + "是文件发现/路径搜索的首选工具。当需要定位符合某模式的文件时优先用 Glob，"
                        + "而不是 Bash 或其他工具；找到路径后可配合 ReadFile 读取内容、Grep 搜索内容。",
                ToolCategory.READ, ToolPermission.READ_ONLY,
                jsonSchema(Map.of(
                        PARAM_PATTERN, stringProp("Glob 模式，如 **/*.java 或 src/**")
                ), List.of(PARAM_PATTERN)));
        this.pathResolver = pathResolver;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String pattern = requiredString(args, PARAM_PATTERN);

        // 使用 PathMatcher 进行匹配
        PathMatcher matcher = pathResolver.workspaceRoot().getFileSystem().getPathMatcher("glob:" + pattern);

        List<String> matchedPaths = new ArrayList<>();
        Set<Path> visitedLinks = new HashSet<>();

        try {
            Files.walkFileTree(pathResolver.workspaceRoot(), EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            addIfMatched(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            // 跳过工作区根目录本身
                            if (dir.equals(pathResolver.workspaceRoot())) return FileVisitResult.CONTINUE;

                            // 检查符号链接
                            if (Files.isSymbolicLink(dir)) {
                                Path real = pathResolver.followLink(dir, visitedLinks);
                                if (real == null) {
                                    return FileVisitResult.SKIP_SUBTREE; // 越界或循环，跳过
                                }
                            }

                            addIfMatched(dir);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE; // 跳过无法访问的文件
                        }

                        private void addIfMatched(Path file) {
                            Path relative = pathResolver.workspaceRoot().relativize(file);
                            if (matcher.matches(relative)) {
                                matchedPaths.add(relative.toString().replace('\\', '/'));
                            }
                        }
                    });
        } catch (IOException e) {
            return ToolResult.error(ctx.callId(), name(), "搜索失败: " + e.getMessage());
        }

        // 稳定排序
        Collections.sort(matchedPaths);

        // 应用输出限制
        ToolOutputLimiter.LimitResult limited = ToolOutputLimiter.limitPaths(matchedPaths);
        if (limited.truncated()) {
            return ToolResult.truncated(ctx.callId(), name(), limited.text());
        }

        return ToolResult.success(ctx.callId(), name(), limited.text());
    }
}