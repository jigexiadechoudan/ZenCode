package dev.zencode.permission;

import dev.zencode.tool.WorkspacePathResolver;

import java.util.Objects;

/**
 * 路径沙箱：校验文件路径是否落在允许范围内，防止越界与符号链接逃逸。
 * <p>
 * 复用现有 {@link WorkspacePathResolver} 的路径边界校验逻辑；允许范围默认等于
 * 工作目录。违反视为硬拦截，不触发 HITL。
 * </p>
 */
public final class PathSandbox {
    private final WorkspacePathResolver resolver;

    public PathSandbox(WorkspacePathResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "路径解析器不能为空");
    }

    /**
     * 给定用户提供的路径，是否安全（工作区内、无越界链接）。
     *
     * @param userPath 用户提供的路径（相对或绝对）
     * @return true 表示允许；越界或无法安全解析时 false
     */
    public boolean isPathSafe(String userPath) {
        if (userPath == null || userPath.isBlank()) return false;
        try {
            resolver.resolve(userPath);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 用于精确授权规则的规范化路径；失败时由调用方按硬拒处理。 */
    String canonicalPath(String userPath) {
        return resolver.resolve(userPath).toString();
    }

    /** 权限检查器预期的工作目录边界。 */
    public java.nio.file.Path workspaceRoot() {
        return resolver.workspaceRoot();
    }
}
