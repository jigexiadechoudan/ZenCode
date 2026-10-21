package dev.zencode.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 指令文件加载器。按优先级发现并加载 {@code ZENCODE.md}（项目级 > 用户级），
 * 支持 {@code @include <路径>} 模块化引用。
 * <p>
 * 加载结果作为一条独立 user 角色消息注入会话历史开头，紧随环境快照轮次之后。
 * </p>
 */
public final class InstructionLoader {

    private static final String INCLUDE_PREFIX = "@include ";
    private static final String PROJECT_FILE_NAME = "ZENCODE.md";
    private static final String USER_FILE_NAME = "ZENCODE.md";

    private final Path workDir;
    private final Path userHome;
    private final MemoryConfig config;

    public InstructionLoader(Path workDir, Path userHome, MemoryConfig config) {
        this.workDir = workDir;
        this.userHome = userHome;
        this.config = config;
    }

    /** 项目级指令文件路径：{@code workDir/ZENCODE.md}。 */
    public Path projectFile() {
        return workDir.resolve(PROJECT_FILE_NAME);
    }

    /** 用户级指令文件路径：{@code userHome/.zencode/ZENCODE.md}。 */
    public Path userFile() {
        return userHome.resolve(".zencode").resolve(USER_FILE_NAME);
    }

    /**
     * 构建完整的指令文本（项目级在前 + 用户级在后），
     * 包含 @include 展开与大小截断。
     * <p>
     * 文件不存在时该级为空、不报错。
     * </p>
     */
    public String buildInstructions() {
        StringBuilder sb = new StringBuilder();

        // 项目级（高优先级在前）
        String projectContent = loadWithIncludes(projectFile(), 0);
        if (!projectContent.isEmpty()) {
            sb.append(projectContent);
        }

        // 用户级
        String userContent = loadWithIncludes(userFile(), 0);
        if (!userContent.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(userContent);
        }

        // 整体截断到 memoryMaxChars
        String result = sb.toString();
        if (result.length() > config.memoryMaxChars()) {
            result = result.substring(0, config.memoryMaxChars());
        }
        return result;
    }

    /**
     * 加载单个文件并展开其中的 @include 引用。
     *
     * @param file  要加载的文件
     * @param depth 当前递归深度
     * @return 展开后的文本，文件不存在或为空时返回空字符串
     */
    private String loadWithIncludes(Path file, int depth) {
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return "";
        }
        if (depth > config.includeMaxDepth()) {
            return "";
        }

        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }

        Path fileDir = file.getParent();
        if (fileDir == null) {
            fileDir = Path.of(".");
        }

        StringBuilder result = new StringBuilder();
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith(INCLUDE_PREFIX)) {
                String includePath = line.substring(INCLUDE_PREFIX.length()).trim();
                if (!includePath.isEmpty()) {
                    String resolved = resolveInclude(fileDir, includePath, depth + 1);
                    if (resolved != null) {
                        result.append(resolved);
                    }
                    // resolved == null 表示被拦截，静默忽略该行
                }
            } else {
                if (result.length() > 0 && !result.toString().endsWith("\n")) {
                    result.append("\n");
                }
                result.append(line);
            }
        }
        return result.toString();
    }

    /**
     * 解析 @include 路径并加载被引用文件内容。
     *
     * @param baseDir    当前指令文件所在目录
     * @param includePath @include 后面跟的路径
     * @return 被引用文件的展开内容，路径被拦截时返回 null
     */
    private String resolveInclude(Path baseDir, String includePath, int depth) {
        // 深度超限
        if (depth > config.includeMaxDepth()) {
            return null;
        }

        // 安全检查：拦截绝对路径
        Path target = Path.of(includePath);
        if (target.isAbsolute()) {
            return null;
        }

        // 安全检查：拦截跳出项目目录的路径（../ 等）
        Path resolved = baseDir.resolve(includePath).normalize();
        if (!resolved.startsWith(baseDir.normalize())) {
            return null;
        }

        // 安全检查：拦截符号链接逃逸（规范化后不在 baseDir 下）
        try {
            Path realBase = baseDir.toRealPath();
            Path realTarget = resolved.toRealPath();
            if (!realTarget.startsWith(realBase)) {
                return null;
            }
        } catch (IOException e) {
            // 文件不存在或无法解析，拦截
            return null;
        }

        return loadWithIncludes(resolved, depth);
    }
}
