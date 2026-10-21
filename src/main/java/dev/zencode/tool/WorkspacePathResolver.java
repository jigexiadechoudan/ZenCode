package dev.zencode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 工作区路径解析器，负责路径边界安全校验。
 * <p>
 * 将相对路径解析到工作目录，允许工作目录内的规范化绝对路径；
 * 对已存在路径解析符号链接真实目标，对新文件校验最近存在父目录的真实路径；
 * 拒绝越界路径、越界链接和无法安全解析的目标。
 * </p>
 */
public final class WorkspacePathResolver {
    private volatile Path workspaceRoot;

    /**
     * @param workspaceRoot 规范化后的工作目录根路径
     */
    public WorkspacePathResolver(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /** 返回规范化后的工作目录根路径。 */
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /** 仅在没有活动运行时，由工作台同步切换执行边界。 */
    public void refreshForNewDirectory(Path directory) {
        workspaceRoot = directory.toAbsolutePath().normalize();
    }

    /**
     * 将用户提供的路径解析为工作区内的安全路径。
     * <p>
     * 如果路径是相对路径，先拼接到工作目录再规范化；
     * 如果是绝对路径，直接规范化。
     * 然后检查是否在工作目录内，拒绝越界。
     * 最后对已存在路径解析符号链接真实目标，拒绝越界链接。
     * </p>
     *
     * @param userPath 用户提供的路径（相对或绝对）
     * @return 规范化后的安全路径
     * @throws IllegalArgumentException 如果路径越界或无法安全解析
     */
    public Path resolve(String userPath) {
        Objects.requireNonNull(userPath, "路径不能为空");
        Path given = Path.of(userPath);
        Path resolved = given.isAbsolute()
                ? given.normalize()
                : workspaceRoot.resolve(given).normalize();

        // 检查是否在工作目录内（直接比较路径前缀）
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("路径越界: " + userPath + "（不在工作目录内）");
        }

        // 如果路径已存在，解析符号链接目标的真实路径
        if (Files.exists(resolved)) {
            try {
                Path real = resolved.toRealPath();
                if (!real.startsWith(workspaceRoot)) {
                    throw new IllegalArgumentException("路径越界: " + userPath + "（符号链接指向工作区外）");
                }
                return real;
            } catch (IOException e) {
                throw new IllegalArgumentException("无法解析路径: " + userPath, e);
            }
        }

        // 对于新建文件，校验最近存在父目录的真实路径
        Path parent = resolved.getParent();
        if (parent != null && !Files.exists(parent)) {
            // 尝试向上查找最近存在的父目录
            Path existingAncestor = findExistingAncestor(resolved);
            if (existingAncestor != null) {
                try {
                    Path realAncestor = existingAncestor.toRealPath();
                    if (!realAncestor.startsWith(workspaceRoot)) {
                        throw new IllegalArgumentException("路径越界: " + userPath + "（父目录符号链接指向工作区外）");
                    }
                } catch (IOException e) {
                    throw new IllegalArgumentException("无法解析父目录路径: " + userPath, e);
                }
            }
        } else if (parent != null && Files.exists(parent)) {
            try {
                Path realParent = parent.toRealPath();
                if (!realParent.startsWith(workspaceRoot)) {
                    throw new IllegalArgumentException("路径越界: " + userPath + "（父目录符号链接指向工作区外）");
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("无法解析父目录路径: " + userPath, e);
            }
        }

        return resolved;
    }

    /**
     * 解析已存在路径的符号链接真实目标。
     * 用于 Glob 等工具在遍历时校验符号链接目标。
     */
    public Path resolveRealPath(Path path) {
        try {
            if (Files.exists(path)) {
                Path real = path.toRealPath();
                if (!real.startsWith(workspaceRoot)) {
                    return null; // 越界链接，返回 null
                }
                return real;
            }
            return path.normalize();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 将路径转为相对工作区的显示路径。
     * 用于工具结果和 TUI 摘要。
     */
    public String relativize(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (normalized.startsWith(workspaceRoot)) {
                return workspaceRoot.relativize(normalized).toString().replace('\\', '/');
            }
            return normalized.toString();
        } catch (Exception e) {
            return path.toString();
        }
    }

    /** 检查路径是否在工作区内的安全路径。 */
    public boolean isPathAllowed(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return normalized.startsWith(workspaceRoot);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检测符号链接是否形成循环。
     * 使用一个 Set 跟踪已访问的链接目标。
     */
    public static boolean isLinkCycle(Path start, Set<Path> visited) {
        try {
            if (!Files.isSymbolicLink(start)) return false;
            Path target = start.toRealPath();
            return !visited.add(target);
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * 安全地跟随符号链接，返回最终真实路径。
     * 如果形成循环或越界，返回 null。
     */
    public Path followLink(Path link, Set<Path> visited) {
        try {
            if (!Files.isSymbolicLink(link)) return link;
            if (!visited.add(link.toRealPath())) return null; // 循环
            Path real = link.toRealPath();
            if (!real.startsWith(workspaceRoot)) return null; // 越界
            return real;
        } catch (IOException e) {
            return null;
        }
    }

    /** 向上查找最近存在的父目录。 */
    private Path findExistingAncestor(Path path) {
        Path current = path.getParent();
        while (current != null) {
            if (Files.exists(current)) return current;
            current = current.getParent();
        }
        return null;
    }
}
