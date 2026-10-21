package dev.zencode.config;

import dev.zencode.cli.CliOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    @Test
    void exampleConfigUsesLauncherApiKey() throws Exception {
        String yaml = Files.readString(Path.of("config.example.yaml"));

        var settings = org.snakeyaml.engine.v2.api.LoadSettings.builder().build();
        var parsed = (java.util.Map<?, ?>) new org.snakeyaml.engine.v2.api.Load(settings).loadFromString(yaml);
        var providers = (java.util.List<?>) parsed.get("providers");
        assertFalse(providers.isEmpty());
        for (Object item : providers) {
            assertEquals("${ZENCODE_API_KEY}", ((java.util.Map<?, ?>) item).get("api_key"));
        }
        assertFalse(yaml.contains("你的APIKey"));
    }

    @Test
    void loadsDefaultAndProviderOverride() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: plain-key
                  - name: two
                    protocol: openai
                    model: gpt
                    base_url: https://example.org
                    api_key: other-key
                    thinking: true
                """);
        ConfigLoader loader = new ConfigLoader();
        ConfigLoader.LoadedConfig loaded = loader.load(CliOptions.parse(new String[]{"--config", file.toString()}));
        assertEquals("one", loaded.selectedProvider().name());
        assertEquals("workbench", loaded.appConfig().theme());
        assertEquals("two", loader.load(CliOptions.parse(new String[]{"--config", file.toString(), "--provider", "two"})).selectedProvider().name());
    }

    @Test
    void parsesPermissionModeAndCliOverride() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                permission_mode: locked
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: plain-key
                """);
        ConfigLoader loader = new ConfigLoader();
        ConfigLoader.LoadedConfig loaded = loader.load(CliOptions.parse(new String[]{"--config", file.toString()}));
        assertEquals(dev.zencode.permission.PermissionMode.LOCKED, loaded.appConfig().permissionMode());
        // CLI 覆盖配置
        ConfigLoader.LoadedConfig overridden = loader.load(CliOptions.parse(
                new String[]{"--config", file.toString(), "--permission-mode", "trust_all"}));
        assertEquals(dev.zencode.permission.PermissionMode.TRUST_ALL, overridden.appConfig().permissionMode());
        // 非法 CLI 值报错
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(
                new String[]{"--permission-mode", "bogus"}));
    }

    @Test
    void rejectsInvalidConfigWithoutExposingKey() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        String secret = "super-secret-value";
        Files.writeString(file, """
                default: missing
                providers:
                  - name: one
                    protocol: unknown
                    model: model
                    base_url: https://example.com
                    api_key: %s
                """.formatted(secret));
        ConfigException error = assertThrows(ConfigException.class,
                () -> new ConfigLoader().load(CliOptions.parse(new String[]{"--config", file.toString()})));
        assertFalse(error.getMessage().contains(secret));
    }

    @Test
    void parsesMcpServers() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: plain-key
                mcp_servers:
                  - name: local
                    transport: stdio
                    command: npx
                    args: [ "-y", "@modelcontextprotocol/server-github" ]
                    env:
                      TOKEN: plain-value
                  - name: remote
                    transport: http
                    url: https://mcp.example.com/api
                    headers:
                      Authorization: "Bearer fixed"
                    timeout: 5000
                """);
        ConfigLoader.LoadedConfig loaded = new ConfigLoader()
                .load(CliOptions.parse(new String[]{"--config", file.toString()}));
        List<McpServerConfig> servers = loaded.appConfig().mcpServers();
        assertEquals(2, servers.size());

        McpServerConfig local = servers.get(0);
        assertEquals("local", local.name());
        assertTrue(local.isStdio());
        assertEquals("npx", local.command());
        assertEquals(List.of("-y", "@modelcontextprotocol/server-github"), local.args());
        assertEquals("plain-value", local.env().get("TOKEN"));
        assertEquals(30_000, local.timeoutMillis());

        McpServerConfig remote = servers.get(1);
        assertEquals("remote", remote.name());
        assertTrue(remote.isHttp());
        assertEquals("https://mcp.example.com/api", remote.url().toString());
        assertEquals("Bearer fixed", remote.headers().get("Authorization"));
        assertEquals(5000, remote.timeoutMillis());
    }

    @Test
    void mcpServersDefaultToEmpty() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: plain-key
                """);
        ConfigLoader.LoadedConfig loaded = new ConfigLoader()
                .load(CliOptions.parse(new String[]{"--config", file.toString()}));
        assertTrue(loaded.appConfig().mcpServers().isEmpty());
    }

    @Test
    void mcpEnvRefExpands() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        String value = System.getenv("PATH");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: plain-key
                mcp_servers:
                  - name: local
                    transport: stdio
                    command: npx
                    env:
                      PATH_REF: "${PATH}"
                """);
        ConfigLoader.LoadedConfig loaded = new ConfigLoader()
                .load(CliOptions.parse(new String[]{"--config", file.toString()}));
        assertEquals(value, loaded.appConfig().mcpServers().get(0).env().get("PATH_REF"));
    }

    @Test
    void rejectsInvalidMcpConfigs() throws Exception {
        // stdio 缺 command
        assertMcpRejected("""
                mcp_servers:
                  - name: a
                    transport: stdio
                """);
        // 非法 transport
        assertMcpRejected("""
                mcp_servers:
                  - name: a
                    transport: websocket
                    command: x
                """);
        // http 缺 url
        assertMcpRejected("""
                mcp_servers:
                  - name: a
                    transport: http
                """);
        // 重复 name
        assertMcpRejected("""
                mcp_servers:
                  - name: a
                    transport: stdio
                    command: x
                  - name: a
                    transport: stdio
                    command: y
                """);
        // 非法 url
        assertMcpRejected("""
                mcp_servers:
                  - name: a
                    transport: http
                    url: not-a-url
                """);
        // 未知字段
        assertMcpRejected("""
                mcp_servers:
                  - name: a
                    transport: stdio
                    command: x
                    bogus: 1
                """);
        // ${ENV} 引用缺失
        assertMcpRejected("""
                mcp_servers:
                  - name: a
                    transport: stdio
                    command: x
                    env:
                      TOKEN: "${ZENCODE_DEFINITELY_NOT_SET_ENV_VAR_12345}"
                """);
    }

    private void assertMcpRejected(String mcpYaml) throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: plain-key
                %s
                """.formatted(mcpYaml));
        assertThrows(ConfigException.class,
                () -> new ConfigLoader().load(CliOptions.parse(new String[]{"--config", file.toString()})));
    }

    @Test
    void parsesContextSection() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: plain-key
                context:
                  window_tokens: 8000
                  compact_ratio: 0.5
                  single_result_threshold: 2048
                  round_total_threshold: 8192
                  max_consecutive_summary_failures: 5
                """);
        ConfigLoader.LoadedConfig loaded = new ConfigLoader()
                .load(CliOptions.parse(new String[]{"--config", file.toString()}));
        ContextConfig context = loaded.appConfig().context();
        assertEquals(8000L, context.windowTokens());
        assertEquals(0.5, context.compactRatio());
        assertEquals(2048, context.singleResultThresholdBytes());
        assertEquals(8192, context.roundTotalThresholdBytes());
        assertEquals(5, context.maxConsecutiveSummaryFailures());
    }

    @Test
    void contextDefaultsWhenAbsent() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: plain-key
                """);
        ConfigLoader.LoadedConfig loaded = new ConfigLoader()
                .load(CliOptions.parse(new String[]{"--config", file.toString()}));
        ContextConfig context = loaded.appConfig().context();
        assertEquals(200_000L, context.windowTokens());
        assertEquals(0.8, context.compactRatio());
        assertEquals(4096, context.singleResultThresholdBytes());
        assertEquals(32 * 1024, context.roundTotalThresholdBytes());
        assertEquals(3, context.maxConsecutiveSummaryFailures());
    }

    @Test
    void rejectsInvalidContext() throws Exception {
        // 未知字段
        assertContextRejected("""
                context:
                  bogus: 1
                """);
        // ratio 大于 1
        assertContextRejected("""
                context:
                  compact_ratio: 2
                """);
        // window_tokens 非正
        assertContextRejected("""
                context:
                  window_tokens: 0
                """);
    }

    private void assertContextRejected(String contextYaml) throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: plain-key
                %s
                """.formatted(contextYaml));
        assertThrows(ConfigException.class,
                () -> new ConfigLoader().load(CliOptions.parse(new String[]{"--config", file.toString()})));
    }

    @Test
    void teamSectionDefaultsToDisabled() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: plain-key
                """);
        ConfigLoader.LoadedConfig loaded = new ConfigLoader()
                .load(CliOptions.parse(new String[]{"--config", file.toString()}));
        assertFalse(loaded.team().coordinatorEnabled());
    }

    @Test
    void teamSectionParsesCoordinatorEnabled() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: plain-key
                team:
                  coordinator:
                    enabled: true
                """);
        ConfigLoader.LoadedConfig loaded = new ConfigLoader()
                .load(CliOptions.parse(new String[]{"--config", file.toString()}));
        assertTrue(loaded.team().coordinatorEnabled());
    }

    @Test
    void teamSectionRejectsUnknownFields() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: plain-key
                team:
                  bogus: 1
                """);
        assertThrows(ConfigException.class,
                () -> new ConfigLoader().load(CliOptions.parse(new String[]{"--config", file.toString()})));
    }

    @Test
    void coordinatorRejectsUnknownFieldsAndNonBoolean() throws Exception {
        Path file = Files.createTempFile("zencode-config", ".yaml");
        Files.writeString(file, """
                default: one
                providers:
                  - name: one
                    protocol: anthropic
                    model: claude
                    base_url: https://example.com
                    api_key: plain-key
                team:
                  coordinator:
                    bogus: 1
                """);
        assertThrows(ConfigException.class,
                () -> new ConfigLoader().load(CliOptions.parse(new String[]{"--config", file.toString()})));
    }
}
