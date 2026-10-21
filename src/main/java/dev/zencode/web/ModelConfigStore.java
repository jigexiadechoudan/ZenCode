package dev.zencode.web;

import dev.zencode.cli.CliOptions;
import dev.zencode.config.ConfigLoader;
import dev.zencode.config.ProviderConfig;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.api.lowlevel.Present;
import org.snakeyaml.engine.v2.api.lowlevel.Serialize;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import org.snakeyaml.engine.v2.nodes.*;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Local configuration editor. Only explicit safe projections may cross the HTTP boundary. */
public final class ModelConfigStore {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final LoadSettings READ_SETTINGS = LoadSettings.builder().setParseComments(true)
            .setAllowDuplicateKeys(false).setMaxAliasesForCollections(0).setCodePointLimit(MAX_BYTES).build();
    private final CliOptions options;
    private final Path path;
    private String activeProvider;
    private String activeModel;
    private String appliedRevision;
    private String applicationError = "";

    public record ProviderSummary(String name, String protocol, String model, String baseUrl,
                                  boolean thinking, boolean keyConfigured, String keySource) {}
    public record Settings(String configPath, String revision, String defaultProvider, String providerOverride,
                           String activeProvider, String activeModel, boolean setupRequired,
                           boolean restartRequired, List<ProviderSummary> providers,
                           boolean applyRequired, String applicationError, boolean runtimeBusy) {
        public Settings withRuntimeBusy(boolean busy) {
            return new Settings(configPath, revision, defaultProvider, providerOverride, activeProvider,
                    activeModel, setupRequired, restartRequired, providers, applyRequired, applicationError, busy);
        }
    }
    /** The callback must persist before publishing a new runtime; failures must retain the old runtime. */
    @FunctionalInterface public interface Application {
        void apply(ConfigLoader.LoadedConfig config, Runnable persist);
    }
    public record Edit(String revision, String originalName, String name, String protocol, String model,
                       String baseUrl, String apiKey, boolean thinking, boolean makeDefault) {
        @Override public String toString() { return "ModelConfigEdit[redacted]"; }
    }
    public static final class SettingsException extends RuntimeException {
        private final int status;
        public SettingsException(int status, String safeMessage) { super(safeMessage); this.status = status; }
        public int status() { return status; }
    }
    private record Document(String revision, MappingNode root) {}

    public ModelConfigStore(CliOptions options, ProviderConfig active) {
        this.options = options;
        this.path = ConfigLoader.configPath(options);
        this.activeProvider = active == null ? "" : active.name();
        this.activeModel = active == null ? "" : active.model();
        String revision;
        try { revision = revision(readBytes()); } catch (Exception ignored) { revision = ""; }
        this.appliedRevision = revision;
    }

    public synchronized Settings read() {
        Document document = document();
        return describe(document);
    }

    public synchronized Settings save(Edit edit) {
        return save(edit, null);
    }

    public synchronized Settings save(Edit edit, Application application) {
        Document document = document();
        if (!document.revision.equals(edit.revision)) throw conflict();
        String name = field(edit.name, 80, "请填写有效的 Provider 名称（最多 80 字符）。");
        String model = field(edit.model, 256, "请填写服务商提供的模型 ID（最多 256 字符）。");
        String protocol = edit.protocol.toLowerCase(Locale.ROOT);
        if (!Set.of("openai", "anthropic").contains(protocol)) throw invalid("请选择支持的协议。");
        String baseUrl = validUrl(edit.baseUrl, protocol);
        SequenceNode providers = providers(document.root);
        MappingNode selected = null;
        for (Node node : providers.getValue()) {
            MappingNode provider = mapping(node);
            String existingName = scalar(provider, "name");
            if (existingName.equals(edit.originalName)) selected = provider;
            if (existingName.equals(name) && !existingName.equals(edit.originalName)) throw invalid("Provider 名称已存在，请选择该配置进行编辑。");
        }
        if (!edit.originalName.isEmpty() && selected == null) throw conflict();
        if (selected == null) selected = new MappingNode(Tag.MAP, new ArrayList<>(), FlowStyle.BLOCK);
        String key = edit.apiKey.trim();
        if (key.isEmpty()) {
            key = scalar(selected, "api_key");
            if (key.isBlank()) throw invalid("请填写 API Key 或 ${ENV_NAME} 环境变量引用。");
            if (!scalar(selected, "base_url").replaceAll("/+$", "").equals(baseUrl)
                    || !scalar(selected, "protocol").equalsIgnoreCase(protocol)) {
                throw invalid("服务地址或协议已改变，请重新填写对应的 API Key，避免把原密钥发送给其他服务。");
            }
        }
        field(key, 8192, "API Key 不能为空、包含换行或超过 8192 字符。");
        if (key.startsWith("${") && (!key.matches("\\$\\{[A-Za-z_][A-Za-z0-9_]*}")
                || System.getenv(key.substring(2, key.length() - 1)) == null
                || System.getenv(key.substring(2, key.length() - 1)).isBlank())) {
            throw invalid("API Key 的环境变量引用无效或当前进程未设置该变量。请设置后重新启动，或直接填写密钥。");
        }
        put(selected, "name", text(name));
        put(selected, "protocol", text(protocol));
        put(selected, "model", text(model));
        put(selected, "base_url", text(baseUrl));
        put(selected, "api_key", text(key));
        put(selected, "thinking", new ScalarNode(Tag.BOOL, String.valueOf(edit.thinking), ScalarStyle.PLAIN));
        if (edit.originalName.isEmpty()) providers.getValue().add(selected);
        put(document.root, "providers", providers);
        String defaultProvider = scalar(document.root, "default");
        if (edit.makeDefault || defaultProvider.isEmpty() || defaultProvider.equals(edit.originalName)) {
            put(document.root, "default", text(name));
        }
        DumpSettings settings = DumpSettings.builder().setDumpComments(true).setSplitLines(false).build();
        String yaml = new Present(settings).emitToString(new Serialize(settings).serializeOne(document.root).iterator());
        final ConfigLoader.LoadedConfig validated;
        // An explicit UI selection applies now; --provider/default only select the next startup.
        CliOptions selection = application == null ? options : new CliOptions(options.configPath(), name,
                options.permissionMode(), options.resume(), options.team(), options.member(), options.spawnTask());
        try { validated = new ConfigLoader().loadYaml(yaml, selection); }
        catch (RuntimeException e) {
            // ConfigException may embed URLs/other private config. Never return its text.
            throw invalid("配置校验未通过，未保存。请检查默认 Provider、启动时的 --provider、环境变量及其他 YAML 配置。");
        }
        byte[] updated = yaml.getBytes(StandardCharsets.UTF_8);
        if (updated.length > MAX_BYTES) throw invalid("配置超过 1 MiB，未保存。");
        if (application == null) {
            atomicWrite(updated, document.revision);
        } else {
            boolean[] persisted = { false };
            try {
                application.apply(validated, () -> {
                    atomicWrite(updated, document.revision);
                    persisted[0] = true;
                });
                activeProvider = validated.selectedProvider().name();
                activeModel = validated.selectedProvider().model();
                appliedRevision = revision(updated);
                applicationError = "";
            } catch (RuntimeException e) {
                if (!persisted[0]) throw e;
                // Initial startup can fail (e.g. invalid hooks). The file is saved, not the runtime.
                applicationError = "配置已保存，但工作台初始化或应用失败。原运行配置未切换；请查看本地脱敏诊断，修正后重新应用。";
            }
        }
        return describe(new Document(revision(updated), document.root));
    }

    private Settings describe(Document document) {
        List<ProviderSummary> summaries = new ArrayList<>();
        for (Node node : providers(document.root).getValue()) {
            MappingNode item = mapping(node);
            String key = scalar(item, "api_key");
            summaries.add(new ProviderSummary(scalar(item, "name"), scalar(item, "protocol").toLowerCase(Locale.ROOT),
                    scalar(item, "model"), publicUrl(scalar(item, "base_url")),
                    "true".equalsIgnoreCase(scalar(item, "thinking")), !key.isBlank(),
                    key.isBlank() ? "none" : key.startsWith("${") ? "environment" : "local"));
        }
        return new Settings(path.toString(), document.revision, scalar(document.root, "default"),
                options.providerName() == null ? "" : options.providerName(), activeProvider, activeModel,
                activeProvider.isEmpty(), !appliedRevision.equals(document.revision), List.copyOf(summaries),
                activeProvider.isEmpty() || !appliedRevision.equals(document.revision) || !applicationError.isEmpty(),
                applicationError, false);
    }

    private Document document() {
        try {
            safeTarget();
            byte[] bytes = readBytes();
            String yaml = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
            Object raw = new Load(READ_SETTINGS).loadFromString(yaml);
            MappingNode root = raw == null ? new MappingNode(Tag.MAP, new ArrayList<>(), FlowStyle.BLOCK)
                    : mapping(new Compose(READ_SETTINGS).composeString(yaml).orElseThrow());
            return new Document(revision(bytes), root);
        } catch (SettingsException e) { throw e; }
        catch (Exception e) { throw new SettingsException(422, "无法读取或解析本地配置。未覆盖原文件，请先检查 YAML 格式、重复字段或文件权限；此编辑器不支持集合别名。"); }
    }

    private byte[] readBytes() throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException();
            return bytes;
        }
    }

    private void safeTarget() throws IOException {
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) throw new IOException();
        }
        if (path.getFileName().toString().equalsIgnoreCase("config.example.yaml")) {
            throw new SettingsException(403, "示例配置不能保存密钥。请改用 config.local.yaml 启动。");
        }
    }

    private void requirePrivateTarget() throws IOException, InterruptedException {
        for (Path directory = path.getParent(); directory != null; directory = directory.getParent()) {
            if (!Files.exists(directory.resolve(".git"))) continue;
            // check-ignore rejects tracked files as well as non-ignored new files.
            Process git = new ProcessBuilder("git", "--no-optional-locks", "check-ignore", "-q", "--", path.toString())
                    .directory(directory.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if (!git.waitFor(3, TimeUnit.SECONDS) || git.exitValue() != 0) {
                    throw new SettingsException(403, "配置文件必须被 Git 忽略且未被跟踪，才能保存密钥。请使用已忽略的 config.local.yaml。");
                }
            } finally { if (git.isAlive()) git.destroyForcibly(); }
            break;
        }
    }

    private void atomicWrite(byte[] bytes, String expected) {
        Path temporary = null;
        try {
            safeTarget();
            requirePrivateTarget();
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), ".zencode-config-", ".tmp");
            var posix = Files.getFileAttributeView(temporary, PosixFileAttributeView.class);
            var acl = Files.getFileAttributeView(temporary, AclFileAttributeView.class);
            if (posix != null) posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            else if (acl != null) acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(temporary)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            else throw new IOException("Private file permissions unavailable");
            Files.write(temporary, bytes);
            safeTarget();
            if (!revision(readBytes()).equals(expected)) throw conflict();
            // Fail closed on filesystems without atomic replacement; never truncate the original file.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (SettingsException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SettingsException(503, "保存已中断，请重新打开设置确认状态。"); }
        catch (Exception e) { throw new SettingsException(500, "无法安全保存配置。请检查文件权限及磁盘是否支持原子替换；原文件未主动清空。"); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }

    private static MappingNode mapping(Node node) {
        if (!(node instanceof MappingNode map)) throw invalid("配置必须使用 YAML 对象结构。");
        return map;
    }
    private static Node get(MappingNode map, String name) {
        return map.getValue().stream().filter(tuple -> tuple.getKeyNode() instanceof ScalarNode key && key.getValue().equals(name))
                .map(NodeTuple::getValueNode).findFirst().orElse(null);
    }
    private static SequenceNode providers(MappingNode root) {
        Node node = get(root, "providers");
        if (node == null) return new SequenceNode(Tag.SEQ, new ArrayList<>(), FlowStyle.BLOCK);
        if (!(node instanceof SequenceNode sequence)) throw invalid("providers 必须是 YAML 列表，请先修复配置。");
        return sequence;
    }
    private static String scalar(MappingNode map, String key) {
        Node node = get(map, key);
        return node instanceof ScalarNode scalar ? scalar.getValue() : "";
    }
    private static ScalarNode text(String value) { return new ScalarNode(Tag.STR, value, ScalarStyle.DOUBLE_QUOTED); }
    private static void put(MappingNode map, String name, Node value) {
        List<NodeTuple> tuples = map.getValue();
        for (int i = 0; i < tuples.size(); i++) {
            NodeTuple tuple = tuples.get(i);
            if (tuple.getKeyNode() instanceof ScalarNode key && key.getValue().equals(name)) {
                Node previous = tuple.getValueNode();
                if (previous != value) {
                    value.setBlockComments(previous.getBlockComments());
                    value.setInLineComments(previous.getInLineComments());
                    value.setEndComments(previous.getEndComments());
                }
                tuples.set(i, new NodeTuple(tuple.getKeyNode(), value));
                return;
            }
        }
        tuples.add(new NodeTuple(new ScalarNode(Tag.STR, name, ScalarStyle.PLAIN), value));
    }
    private static String field(String value, int limit, String message) {
        String result = value.trim();
        if (result.isBlank() || result.length() > limit || result.chars().anyMatch(Character::isISOControl)) throw invalid(message);
        return result;
    }
    private static String validUrl(String value, String protocol) {
        String result = field(value, 2048, "请填写有效的 Base URL。").replaceAll("/+$", "");
        try {
            URI uri = URI.create(result);
            if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getPort() > 65535 || uri.getPort() == 0
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw invalid("Base URL 必须是 HTTP(S) 根地址，不能包含账号、密码、查询参数或片段。"); }
        if (result.endsWith("/chat/completions") || result.endsWith("/messages") || result.endsWith("/responses")) {
            throw invalid("请填写 API 根地址，不要填写完整请求接口；当前 OpenAI 协议使用 Chat Completions。");
        }
        if (protocol.equals("anthropic") && result.endsWith("/v1")) throw invalid("Anthropic 协议会追加 /v1/messages，根地址末尾不要再填写 /v1。");
        return result;
    }
    private static String publicUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null ? value : "";
        } catch (IllegalArgumentException e) { return ""; }
    }
    private static String revision(byte[] bytes) {
        if (bytes == null) return "missing";
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException("配置版本计算失败"); }
    }
    private static SettingsException invalid(String message) { return new SettingsException(400, message); }
    private static SettingsException conflict() { return new SettingsException(409, "配置已被其他页面或编辑器修改，请重新加载后再保存。"); }
}
