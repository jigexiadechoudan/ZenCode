package dev.zencode.skill;

import dev.zencode.tool.ToolRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Skill 加载器：三级搜索路径（内置 > 用户 > 项目，同名高优先覆盖）加载并校验。
 * <p>
 * 单个 Skill 解析失败 → 记日志跳过，不阻断整体；全部加载后执行 fail-fast：
 * 校验每个 Skill 的 allowedTools 引用必须存在于「已注册工具 ∪ 系统工具 ∪ 目录型自带工具」，
 * 否则该 Skill 跳过。
 * </p>
 */
public final class SkillLoader {

    private final Path workDir;
    private final Path userHome;
    private final SkillConfig config;

    public SkillLoader(Path workDir, Path userHome, SkillConfig config) {
        this.workDir = workDir;
        this.userHome = userHome;
        this.config = config == null ? SkillConfig.defaults() : config;
    }

    /**
     * 加载全部 Skill。
     *
     * @param toolRegistry 已注册工具（含系统工具），用于 fail-fast 依赖校验
     * @return name → SkillDefinition，插入序稳定，高优先覆盖低优先
     */
    public Map<String, SkillDefinition> loadAll(ToolRegistry toolRegistry) {
        Map<String, SkillDefinition> result = new LinkedHashMap<>();
        loadBuiltin(result);
        loadDirectory(userHome.resolve(SkillConfig.RELATIVE_DIR), SkillSource.Level.USER, result);
        loadDirectory(workDir.resolve(SkillConfig.RELATIVE_DIR), SkillSource.Level.PROJECT, result);
        return failFast(result, toolRegistry);
    }

    // ---- 目录扫描（用户级 / 项目级） ----

    private void loadDirectory(Path dir, SkillSource.Level level, Map<String, SkillDefinition> result) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(path -> {
                String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
                if (Files.isDirectory(path)) {
                    Path skillMd = path.resolve("SKILL.md");
                    if (Files.isRegularFile(skillMd)) {
                        parseAndPut(skillMd, new SkillSource(level, skillMd, true), result);
                    }
                } else if (fileName.toLowerCase(Locale.ROOT).endsWith(".md")
                        && !fileName.equalsIgnoreCase("SKILL.md")) {
                    parseAndPut(path, new SkillSource(level, path, false), result);
                }
            });
        } catch (IOException e) {
            System.err.println("[skill] 扫描目录失败 " + dir + ": " + e.getMessage());
        }
    }

    private void parseAndPut(Path file, SkillSource source, Map<String, SkillDefinition> result) {
        try {
            SkillDefinition skill = SkillParser.parse(file, source);
            if (skill.body().length() > config.maxBodyChars()) {
                System.err.println("[skill] " + skill.name() + " 正文超长，截断到 " + config.maxBodyChars() + " 字符");
                skill = new SkillDefinition(skill.name(), skill.description(), skill.allowedTools(),
                        skill.mode(), skill.model(), skill.context(), skill.hidden(),
                        skill.body().substring(0, config.maxBodyChars()), skill.source());
            }
            result.put(skill.name(), skill);
        } catch (SkillParseException e) {
            System.err.println("[skill] 跳过 " + file + ": " + e.getMessage());
        }
    }

    // ---- 内置层（classpath /skills） ----

    private void loadBuiltin(Map<String, SkillDefinition> result) {
        URL url = SkillLoader.class.getResource("/skills");
        if (url == null) return;
        if ("jar".equals(url.getProtocol())) {
            loadBuiltinFromJar(url, result);
        } else if ("file".equals(url.getProtocol())) {
            loadBuiltinFromDir(url, result);
        }
    }

    private void loadBuiltinFromJar(URL url, Map<String, SkillDefinition> result) {
        try {
            URLConnection connection = url.openConnection();
            if (!(connection instanceof JarURLConnection jarConnection)) return;
            try (JarFile jar = jarConnection.getJarFile()) {
                String prefix = "skills/";
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith(prefix) || entry.isDirectory()) continue;
                    String rel = name.substring(prefix.length());
                    if (rel.endsWith(".md")) {
                        readBuiltin(rel, result);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[skill] 读取内置 Skill 失败: " + e.getMessage());
        }
    }

    private void loadBuiltinFromDir(URL url, Map<String, SkillDefinition> result) {
        try {
            Path dir = Path.of(url.toURI());
            try (Stream<Path> entries = Files.list(dir)) {
                entries.forEach(path -> {
                    String rel = dir.relativize(path).toString().replace('\\', '/');
                    if (Files.isRegularFile(path) && rel.endsWith(".md")) {
                        readBuiltin(rel, result);
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("[skill] 读取内置 Skill 失败: " + e.getMessage());
        }
    }

    /** 读取并解析 classpath 内置 Skill；相对路径 rel 形如 name.md 或 name/SKILL.md。 */
    private void readBuiltin(String rel, Map<String, SkillDefinition> result) {
        try (InputStream in = SkillLoader.class.getResourceAsStream("/skills/" + rel)) {
            if (in == null) return;
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            boolean directory = rel.indexOf('/') >= 0;
            SkillSource source = new SkillSource(SkillSource.Level.BUILTIN,
                    Path.of("/builtin/skills", rel), directory);
            SkillDefinition skill = SkillParser.parseText(text, source);
            result.put(skill.name(), skill);
        } catch (IOException e) {
            System.err.println("[skill] 读取内置 Skill " + rel + " 失败: " + e.getMessage());
        }
    }

    // ---- fail-fast 依赖校验 ----

    private Map<String, SkillDefinition> failFast(Map<String, SkillDefinition> all, ToolRegistry toolRegistry) {
        Set<String> known = new HashSet<>();
        if (toolRegistry != null) {
            for (String tool : toolRegistry.toolNames()) {
                known.add(tool.toLowerCase(Locale.ROOT));
            }
        }
        for (String tool : SystemToolNames.ALL) {
            known.add(tool.toLowerCase(Locale.ROOT));
        }
        for (SkillDefinition skill : all.values()) {
            if (skill.source().directory()) {
                try {
                    for (String tool : SkillToolCatalog.toolNames(skill.source().skillDir())) {
                        known.add(tool.toLowerCase(Locale.ROOT));
                    }
                } catch (SkillParseException e) {
                    System.err.println("[skill] " + skill.name() + " 目录工具解析失败: " + e.getMessage());
                }
            }
        }
        Map<String, SkillDefinition> ok = new LinkedHashMap<>();
        for (Map.Entry<String, SkillDefinition> entry : all.entrySet()) {
            SkillDefinition skill = entry.getValue();
            List<String> missing = skill.allowedTools().stream()
                    .filter(tool -> !known.contains(tool.toLowerCase(Locale.ROOT)))
                    .toList();
            if (!missing.isEmpty()) {
                System.err.println("[skill] 跳过 " + skill.name()
                        + ": allowedTools 引用了不存在的工具 " + missing);
                continue;
            }
            ok.put(entry.getKey(), skill);
        }
        return ok;
    }
}
