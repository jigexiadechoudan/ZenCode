package dev.zencode.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 记忆文件读写。负责项目级与用户级 {@code memories.md} 的读取和写入。
 * <p>
 * 用户偏好/纠正反馈写入用户级；项目知识/参考资料写入项目级。
 * 新会话启动时自动加载记忆内容，作为独立 user 消息注入指令消息之后。
 * </p>
 */
public final class MemoryStore {

    private static final String MEMORIES_FILE = "memories.md";

    private final Path workDir;
    private final Path userHome;
    private final MemoryConfig config;

    public MemoryStore(Path workDir, Path userHome, MemoryConfig config) {
        this.workDir = workDir;
        this.userHome = userHome;
        this.config = config;
    }

    /** 记忆作用域。 */
    public enum Scope {
        /** 项目级记忆（项目知识、参考资料）。 */
        PROJECT,
        /** 用户级记忆（用户偏好、纠正反馈）。 */
        USER
    }

    /** 项目级记忆文件路径：{@code workDir/.zencode/memories.md}。 */
    public Path projectFile() {
        return workDir.resolve(".zencode").resolve(MEMORIES_FILE);
    }

    /** 用户级记忆文件路径：{@code userHome/.zencode/memories.md}。 */
    public Path userFile() {
        return userHome.resolve(".zencode").resolve(MEMORIES_FILE);
    }

    /** 读取指定作用域的记忆内容，文件不存在返回空字符串。 */
    public String read(Scope scope) {
        Path file = fileForScope(scope);
        if (!Files.exists(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 合并读取项目级与用户级记忆（项目在前），
     * 结果受 {@code memoryMaxChars} 截断。
     */
    public String readMerged() {
        StringBuilder sb = new StringBuilder();

        String projectContent = read(Scope.PROJECT);
        if (!projectContent.isEmpty()) {
            sb.append(projectContent);
        }

        String userContent = read(Scope.USER);
        if (!userContent.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(userContent);
        }

        String result = sb.toString();
        if (result.length() > config.memoryMaxChars()) {
            result = result.substring(0, config.memoryMaxChars());
        }
        return result;
    }

    /** 覆盖写入指定作用域的记忆文件（UTF-8）。 */
    public boolean write(Scope scope, String content) {
        Path file = fileForScope(scope);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private Path fileForScope(Scope scope) {
        return switch (scope) {
            case PROJECT -> projectFile();
            case USER -> userFile();
        };
    }
}
