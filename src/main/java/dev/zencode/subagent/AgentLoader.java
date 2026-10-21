package dev.zencode.subagent;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 子 Agent 定义多来源加载器。
 * <p>
 * 按 {@code PLUGIN → BUILTIN → USER → PROJECT} 顺序加载，同名定义由后加载（更高优先级）覆盖；
 * 内置层同时支持文件目录（测试用）与 classpath {@code /agents}（打包运行用）。
 * 单个文件解析失败仅记日志跳过，不阻断其余加载。
 * </p>
 */
public final class AgentLoader {

    private static final System.Logger LOG = System.getLogger("dev.zencode.subagent.AgentLoader");

    /** 默认关闭的内置角色（需配置开关显式启用）。 */
    private static final Set<String> DEFAULT_DISABLED = Set.of("review");

    private final Path pluginDir;
    private final Path builtinDir;
    private final Path userDir;
    private final Path projectDir;
    private final Map<String, AgentDefinition> loaded = new LinkedHashMap<>();
    private final Set<String> enabledNames = ConcurrentHashMap.newKeySet();

    /**
     * @param pluginDir  插件级目录（可空）
     * @param builtinDir 内置级文件目录（可空；classpath /agents 始终参与内置层）
     * @param userDir    用户级目录（可空）
     * @param projectDir 项目级目录（可空）
     */
    public AgentLoader(Path pluginDir, Path builtinDir, Path userDir, Path projectDir) {
        this.pluginDir = pluginDir;
        this.builtinDir = builtinDir;
        this.userDir = userDir;
        this.projectDir = projectDir;
    }

    /** 全量加载并按优先级覆盖，返回 name → definition。 */
    public synchronized Map<String, AgentDefinition> load() {
        loaded.clear();
        loadDirectory(pluginDir, AgentSource.Level.PLUGIN);
        loadDirectory(builtinDir, AgentSource.Level.BUILTIN);
        loadBuiltinClasspath();
        loadDirectory(userDir, AgentSource.Level.USER);
        loadDirectory(projectDir, AgentSource.Level.PROJECT);
        return Map.copyOf(loaded);
    }

    /** 按名查找（需先 load；未 load 则返回空）。 */
    public Optional<AgentDefinition> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(loaded.get(name.toLowerCase(Locale.ROOT)));
    }

    /** 角色是否启用：默认启用；review 等默认关闭角色需显式 {@link #enable(String)}。 */
    public boolean isEnabled(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (DEFAULT_DISABLED.contains(n)) {
            return enabledNames.contains(n);
        }
        return true;
    }

    /** 显式启用某个默认关闭的角色（如 review）。 */
    public void enable(String name) {
        if (name != null && !name.isBlank()) {
            enabledNames.add(name.toLowerCase(Locale.ROOT));
        }
    }

    private void loadDirectory(Path dir, AgentSource.Level level) {
        for (AgentSource as : scan(dir, level)) {
            try {
                AgentDefinition def = AgentParser.parse(as.path(), as);
                loaded.put(def.name(), def); // 同名后加载覆盖
            } catch (AgentParseException e) {
                LOG.log(System.Logger.Level.WARNING, "跳过非法 Agent 定义: " + e.getMessage());
            }
        }
    }

    // ---- 内置层（classpath /agents） ----

    private void loadBuiltinClasspath() {
        URL url = AgentLoader.class.getResource("/agents");
        if (url == null) return;
        if ("jar".equals(url.getProtocol())) {
            loadBuiltinFromJar(url);
        } else if ("file".equals(url.getProtocol())) {
            loadBuiltinFromDir(url);
        }
    }

    private void loadBuiltinFromJar(URL url) {
        try {
            URLConnection connection = url.openConnection();
            if (!(connection instanceof JarURLConnection jarConnection)) return;
            try (JarFile jar = jarConnection.getJarFile()) {
                String prefix = "agents/";
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith(prefix) || entry.isDirectory()) continue;
                    String rel = name.substring(prefix.length());
                    if (rel.endsWith(".md")) {
                        readBuiltin(rel);
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "读取内置 Agent 失败: " + e.getMessage());
        }
    }

    private void loadBuiltinFromDir(URL url) {
        try {
            Path dir = Path.of(url.toURI());
            try (var entries = Files.list(dir)) {
                entries.forEach(path -> {
                    String rel = dir.relativize(path).toString().replace('\\', '/');
                    if (Files.isRegularFile(path) && rel.endsWith(".md")) {
                        readBuiltin(rel);
                    }
                });
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "读取内置 Agent 失败: " + e.getMessage());
        }
    }

    private void readBuiltin(String rel) {
        try (InputStream in = AgentLoader.class.getResourceAsStream("/agents/" + rel)) {
            if (in == null) return;
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            AgentSource source = new AgentSource(AgentSource.Level.BUILTIN, Path.of("/builtin/agents", rel));
            AgentDefinition def = AgentParser.parseText(text, source);
            loaded.put(def.name(), def);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "读取内置 Agent " + rel + " 失败: " + e.getMessage());
        }
    }

    private static List<AgentSource> scan(Path dir, AgentSource.Level level) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<AgentSource> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> result.add(new AgentSource(level, p)));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "扫描 Agent 目录失败: " + dir + " - " + e.getMessage());
        }
        return result;
    }
}
