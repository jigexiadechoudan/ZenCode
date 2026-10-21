package dev.zencode.subagent;

import dev.zencode.config.ProviderConfig;
import dev.zencode.hook.HookEngine;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.SwitchableProvider;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.worktree.WorktreeManager;

import java.nio.file.Path;

/**
 * 子 Agent 运行上下文：区分共享基础设施与隔离边界的载体。
 * <p>
 * parentProvider/baseConfig 用于复用连接或按模型换连；hookEngine/traceRegistry 可空；
 * sourceRegistry 为全量工具集（由 {@link SubagentRunner} 过滤后重建）；
 * worktreeManager 可空（隔离模式经它创建独立 worktree）。
 * </p>
 */
public record SubagentContext(
        ChatProvider parentProvider,
        ProviderConfig baseConfig,
        Path workingDirectory,
        HookEngine hookEngine,
        TraceRegistry traceRegistry,
        ToolRegistry sourceRegistry,
        WorktreeManager worktreeManager) {

    public Path workingDirectory() {
        return worktreeManager == null ? workingDirectory : worktreeManager.currentDirectory();
    }

    /** 指定模型的子 Agent 也必须使用当前生效的地址、密钥与协议。 */
    public ProviderConfig baseConfig() {
        return parentProvider instanceof SwitchableProvider switchable
                ? switchable.config() : baseConfig;
    }
}
