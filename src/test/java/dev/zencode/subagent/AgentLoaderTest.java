package dev.zencode.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoaderTest {

    @TempDir
    Path tmp;

    private static void def(Path dir, String name, String desc) throws IOException {
        Files.writeString(dir.resolve(name + ".md"),
                "---\nname: " + name + "\ndescription: " + desc + "\n---\n正文\n",
                StandardCharsets.UTF_8);
    }

    @Test
    void 四级同名按项目优先覆盖() throws IOException {
        Path plugin = Files.createDirectory(tmp.resolve("plugin"));
        Path builtin = Files.createDirectory(tmp.resolve("builtin"));
        Path user = Files.createDirectory(tmp.resolve("user"));
        Path project = Files.createDirectory(tmp.resolve("project"));

        def(plugin, "explore", "来自插件");
        def(builtin, "explore", "来自内置");
        def(user, "explore", "来自用户");
        def(project, "explore", "来自项目");

        AgentLoader loader = new AgentLoader(plugin, builtin, user, project);
        Map<String, AgentDefinition> loaded = loader.load();
        AgentDefinition explore = loaded.get("explore");
        assertNotNull(explore);
        assertEquals("来自项目", explore.description());
        assertEquals(AgentSource.Level.PROJECT, explore.source().level());
    }

    @Test
    void 不同来源同名后者覆盖前者() throws IOException {
        Path plugin = Files.createDirectory(tmp.resolve("plugin"));
        Path project = Files.createDirectory(tmp.resolve("project"));

        def(plugin, "general-purpose", "插件版");
        def(project, "general-purpose", "项目版");

        AgentLoader loader = new AgentLoader(plugin, null, null, project);
        assertEquals("项目版", loader.load().get("general-purpose").description());
    }

    @Test
    void 非法文件跳过不阻断其余加载() throws IOException {
        Path project = Files.createDirectory(tmp.resolve("project"));
        def(project, "explore", "探索");
        // 缺 name 的非法定义
        Files.writeString(project.resolve("bad.md"),
                "---\ndescription: 缺name\n---\n正文\n", StandardCharsets.UTF_8);
        def(project, "plan", "规划");

        AgentLoader loader = new AgentLoader(null, null, null, project);
        Map<String, AgentDefinition> loaded = loader.load();
        assertTrue(loaded.containsKey("explore"));
        assertTrue(loaded.containsKey("plan"));
        assertFalse(loaded.containsKey("bad"));
        assertEquals(AgentSource.Level.PROJECT, loaded.get("explore").source().level());
    }

    @Test
    void find命中与未命中() throws IOException {
        Path builtin = Files.createDirectory(tmp.resolve("builtin"));
        def(builtin, "plan", "规划");
        AgentLoader loader = new AgentLoader(null, builtin, null, null);
        loader.load();
        assertTrue(loader.find("plan").isPresent());
        assertTrue(loader.find("PLAN").isPresent()); // 大小写不敏感
        assertTrue(loader.find("nope").isEmpty());
    }

    @Test
    void review默认关闭enable后启用() {
        AgentLoader loader = new AgentLoader(null, null, null, null);
        assertTrue(loader.isEnabled("explore"));   // 默认角色启用
        assertFalse(loader.isEnabled("review"));   // review 默认关
        loader.enable("review");
        assertTrue(loader.isEnabled("review"));    // 开关后启用
    }

    @Test
    void 内置classpath资源可加载() {
        AgentLoader loader = new AgentLoader(null, null, null, null);
        Map<String, AgentDefinition> loaded = loader.load();
        assertTrue(loaded.containsKey("explore"), loaded.keySet().toString());
        assertTrue(loaded.containsKey("plan"));
        assertTrue(loaded.containsKey("general-purpose"));
        assertTrue(loaded.containsKey("review"));
    }
}
