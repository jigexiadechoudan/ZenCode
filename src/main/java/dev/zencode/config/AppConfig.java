package dev.zencode.config;

import dev.zencode.memory.MemoryConfig;
import dev.zencode.permission.PermissionMode;

import java.util.List;

public record AppConfig(String defaultProvider, List<ProviderConfig> providers, String theme,
                        PermissionMode permissionMode, List<McpServerConfig> mcpServers,
                        ContextConfig context, MemoryConfig memory, AgentConfig agents) {
    public AppConfig {
        providers = List.copyOf(providers);
        theme = theme == null || theme.isBlank() ? "workbench" : theme;
        permissionMode = permissionMode == null ? PermissionMode.DEFAULT : permissionMode;
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        context = context == null ? ContextConfig.defaults() : context;
        memory = memory == null ? MemoryConfig.defaults() : memory;
        agents = agents == null ? AgentConfig.defaults() : agents;
    }

    public AppConfig(String defaultProvider, List<ProviderConfig> providers, String theme,
                     PermissionMode permissionMode, List<McpServerConfig> mcpServers,
                     ContextConfig context, MemoryConfig memory) {
        this(defaultProvider, providers, theme, permissionMode, mcpServers, context, memory, AgentConfig.defaults());
    }

    public AppConfig(String defaultProvider, List<ProviderConfig> providers, String theme,
                     PermissionMode permissionMode, List<McpServerConfig> mcpServers, ContextConfig context) {
        this(defaultProvider, providers, theme, permissionMode, mcpServers, context, MemoryConfig.defaults());
    }

    public AppConfig(String defaultProvider, List<ProviderConfig> providers, String theme,
                     PermissionMode permissionMode, List<McpServerConfig> mcpServers) {
        this(defaultProvider, providers, theme, permissionMode, mcpServers, ContextConfig.defaults(), MemoryConfig.defaults());
    }

    public AppConfig(String defaultProvider, List<ProviderConfig> providers, String theme,
                     PermissionMode permissionMode) {
        this(defaultProvider, providers, theme, permissionMode, List.of(), ContextConfig.defaults(), MemoryConfig.defaults());
    }

    public AppConfig(String defaultProvider, List<ProviderConfig> providers, String theme) {
        this(defaultProvider, providers, theme, PermissionMode.DEFAULT, List.of(), ContextConfig.defaults(), MemoryConfig.defaults());
    }

    public AppConfig(String defaultProvider, List<ProviderConfig> providers) {
        this(defaultProvider, providers, "workbench", PermissionMode.DEFAULT, List.of(), ContextConfig.defaults(), MemoryConfig.defaults());
    }

    public ProviderConfig provider(String name) {
        return providers.stream().filter(provider -> provider.name().equals(name)).findFirst().orElse(null);
    }
}
