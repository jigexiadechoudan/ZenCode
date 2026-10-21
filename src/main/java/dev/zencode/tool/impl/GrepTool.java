package dev.zencode.tool.impl;

import dev.zencode.tool.*;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 在工作区内使用正则表达式搜索文本，支持可选文件匹配范围和大小写敏感设置。
 * <p>
 * 结果包含相对路径、行号和匹配行文本，按文件路径和行号稳定排序。
 * 结果最多返回 32 KiB，超出时按顺序保留完整匹配行并标记截断。
 * 目录、二进制文件和非 UTF-8 文件被跳过并记录跳过原因。
 * </p>
 */
public final class GrepTool extends BaseTool {
    private static final String PARAM_PATTERN = "pattern";
    private static final String PARAM_INCLUDE = "include";
    private static final String PARAM_CASE_SENSITIVE = "case_sensitive";

    private final WorkspacePathResolver pathResolver;

    public GrepTool(WorkspacePathResolver pathResolver) {
        super("Grep",
                "在工作区内使用正则表达式搜索文本文件内容，是内容搜索的首选工具，返回 路径:行号:匹配行。"
                        + "当需要按内容查找时优先用 Grep，而不是 Bash 或其他工具；配合 Glob 定位文件范围、"
                        + "ReadFile 查看上下文。支持文件过滤（include）与大小写设置。目录、二进制与非 UTF-8 文件被跳过。",
                ToolCategory.READ, ToolPermission.READ_ONLY,
                BaseTool.jsonSchema(Map.of(
                        PARAM_PATTERN, BaseTool.stringProp("搜索的正则表达式"),
                        PARAM_INCLUDE, BaseTool.stringProp("可选：只搜索匹配此 Glob 模式的文件（如 *.java）"),
                        PARAM_CASE_SENSITIVE, BaseTool.booleanProp("是否区分大小写（默认 true）")
                ), List.of(PARAM_PATTERN)));
        this.pathResolver = pathResolver;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String regex = requiredString(args, PARAM_PATTERN);
        String includePattern = optionalString(args, PARAM_INCLUDE, null);
        boolean caseSensitive = optionalBoolean(args, PARAM_CASE_SENSITIVE, true);

        // 编译正则
        Pattern regexPattern;
        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            regexPattern = Pattern.compile(regex, flags);
        } catch (PatternSyntaxException e) {
            return ToolResult.error(ctx.callId(), name(), "正则表达式无效: " + e.getMessage());
        }

        // 编译文件过滤（可选）
        PathMatcher fileMatcher = includePattern != null
                ? pathResolver.workspaceRoot().getFileSystem().getPathMatcher("glob:" + includePattern)
                : null;

        // 收集匹配结果
        List<MatchResult> matches = new ArrayList<>();
        List<String> skippedPaths = new ArrayList<>();

        try {
            Files.walkFileTree(pathResolver.workspaceRoot(), Set.of(), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            // 检查是否匹配文件过滤
                            Path relative = pathResolver.workspaceRoot().relativize(file);
                            if (fileMatcher != null && !fileMatcher.matches(relative)) {
                                return FileVisitResult.CONTINUE;
                            }

                            // 跳过目录
                            if (attrs.isDirectory()) return FileVisitResult.CONTINUE;

                            // 检查是否为常规文件
                            if (!attrs.isRegularFile()) {
                                skippedPaths.add(relative.toString().replace('\\', '/') + "（非普通文件）");
                                return FileVisitResult.CONTINUE;
                            }

                            // 检查文件大小，避免处理过大文件
                            if (attrs.size() > 10 * 1024 * 1024) { // 10 MB
                                skippedPaths.add(relative.toString().replace('\\', '/') + "（文件过大）");
                                return FileVisitResult.CONTINUE;
                            }

                            // 读取并搜索
                            try {
                                byte[] bytes = Files.readAllBytes(file);
                                // 检查是否为 UTF-8 或纯文本
                                if (isBinary(bytes)) {
                                    skippedPaths.add(relative.toString().replace('\\', '/') + "（二进制文件）");
                                    return FileVisitResult.CONTINUE;
                                }

                                String content = new String(bytes, StandardCharsets.UTF_8);
                                // 再次检查是否为有效 UTF-8（通过解码验证）
                                if (!isValidUtf8(bytes)) {
                                    skippedPaths.add(relative.toString().replace('\\', '/') + "（非 UTF-8 编码）");
                                    return FileVisitResult.CONTINUE;
                                }

                                String[] lines = content.split("\n", -1);
                                String relPath = relative.toString().replace('\\', '/');
                                for (int i = 0; i < lines.length; i++) {
                                    if (regexPattern.matcher(lines[i]).find()) {
                                        matches.add(new MatchResult(relPath, i + 1, lines[i].trim()));
                                    }
                                }
                            } catch (MalformedInputException e) {
                                skippedPaths.add(relative.toString().replace('\\', '/') + "（非 UTF-8 编码）");
                            } catch (IOException e) {
                                skippedPaths.add(relative.toString().replace('\\', '/') + "（读取失败）");
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            return ToolResult.error(ctx.callId(), name(), "搜索失败: " + e.getMessage());
        }

        // 按路径和行号排序
        Collections.sort(matches);

        // 构建输出
        StringBuilder result = new StringBuilder();
        if (matches.isEmpty()) {
            return ToolResult.success(ctx.callId(), name(), "未找到匹配结果");
        }

        for (MatchResult match : matches) {
            String line = match.path + ":" + match.line + ":" + match.content;
            // 检查是否超出 32 KiB
            byte[] candidateBytes = (result.length() > 0 ? result.toString() + "\n" + line : line).getBytes(StandardCharsets.UTF_8);
            if (candidateBytes.length > ToolOutputLimiter.MAX_OUTPUT_BYTES) {
                result.append("\n...(输出截断，剩余匹配行被省略)");
                return ToolResult.truncated(ctx.callId(), name(), result.toString());
            }
            if (result.length() > 0) result.append("\n");
            result.append(line);
        }

        // 如果有跳过信息，附加上
        if (!skippedPaths.isEmpty()) {
            result.append("\n---\n已跳过 ").append(skippedPaths.size()).append(" 个文件:");
            for (String skip : skippedPaths) {
                String line = "\n  " + skip;
                byte[] candidateBytes = (result.toString() + line).getBytes(StandardCharsets.UTF_8);
                if (candidateBytes.length > ToolOutputLimiter.MAX_OUTPUT_BYTES) break;
                result.append(line);
            }
        }

        return ToolResult.success(ctx.callId(), name(), result.toString());
    }

    /** 简单检测是否为二进制文件（检查空字节）。 */
    private static boolean isBinary(byte[] bytes) {
        int max = Math.min(bytes.length, 8192);
        for (int i = 0; i < max; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    /** 检查是否为有效 UTF-8 编码。 */
    private static boolean isValidUtf8(byte[] bytes) {
        try {
            new String(bytes, StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 匹配结果。 */
    private record MatchResult(String path, int line, String content) implements Comparable<MatchResult> {
        @Override
        public int compareTo(MatchResult other) {
            int pathCmp = this.path.compareTo(other.path);
            if (pathCmp != 0) return pathCmp;
            return Integer.compare(this.line, other.line);
        }
    }
}