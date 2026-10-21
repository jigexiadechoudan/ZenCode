package dev.zencode.skill;

import dev.zencode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {

    @TempDir
    Path tempDir;

    private Path workDir() {
        return tempDir.resolve("work");
    }

    private Path userHome() {
        return tempDir.resolve("home");
    }

    private Path projectSkills() {
        return workDir().resolve(".zencode").resolve("skills");
    }

    private Path userSkills() {
        return userHome().resolve(".zencode").resolve("skills");
    }

    private SkillLoader loader() {
        return new SkillLoader(workDir(), userHome(), SkillConfig.defaults());
    }

    private ToolRegistry registryWithKnownTools() {
        return ToolRegistry.createDefault(workDir());
    }

    private void writeSkill(Path dir, String fileName, String frontmatter, String body) throws IOException {
        Files.createDirectories(dir);
        String content = "---\n" + frontmatter + "---\n" + body;
        Files.writeString(dir.resolve(fileName), content);
    }

    @Test
    void 项目级同名覆盖用户级() throws IOException {
        writeSkill(userSkills(), "deploy.md", "name: deploy\nmode: inline\ndescription: 用户级\n", "body");
        writeSkill(projectSkills(), "deploy.md", "name: deploy\nmode: inline\ndescription: 项目级\n", "body");

        Map<String, SkillDefinition> loaded = loader().loadAll(registryWithKnownTools());
        assertTrue(loaded.containsKey("deploy"));
        assertEquals("项目级", loaded.get("deploy").description());
        assertEquals(SkillSource.Level.PROJECT, loaded.get("deploy").source().level());
    }

    @Test
    void 单个坏文件跳过不阻断其他() throws IOException {
        writeSkill(projectSkills(), "good.md", "name: good\nmode: inline\ndescription: 好文件\n", "body");
        writeSkill(projectSkills(), "bad.md", "name: bad\ndescription: 缺 mode\n", "body");

        Map<String, SkillDefinition> loaded = loader().loadAll(registryWithKnownTools());
        assertTrue(loaded.containsKey("good"));
        assertFalse(loaded.containsKey("bad"));
    }

    @Test
    void failFast白名单引用不存在工具则跳过() throws IOException {
        writeSkill(projectSkills(), "ok.md",
                "name: ok\nmode: inline\ndescription: 合法白名单\nallowedTools: [Bash, ReadFile]\n", "body");
        writeSkill(projectSkills(), "broken.md",
                "name: broken\nmode: inline\ndescription: 非法白名单\nallowedTools: [NoSuchTool]\n", "body");

        Map<String, SkillDefinition> loaded = loader().loadAll(registryWithKnownTools());
        assertTrue(loaded.containsKey("ok"));
        assertFalse(loaded.containsKey("broken"));
    }

    @Test
    void 目录型Skill按SKILL加载且工具名参与failFast() throws IOException {
        Path dir = projectSkills().resolve("pkg");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: pkg\nmode: inline\ndescription: 目录型\nallowedTools: [Bash, scan]\n---\nbody");
        Files.writeString(dir.resolve("tool.json"),
                "{ \"tools\": [ { \"name\": \"scan\", \"description\": \"扫描\", \"script\": \"scan.cmd\" } ] }");
        Files.writeString(dir.resolve("scan.cmd"), "@echo off\necho scan");

        Map<String, SkillDefinition> loaded = loader().loadAll(registryWithKnownTools());
        assertTrue(loaded.containsKey("pkg"));
        assertTrue(loaded.get("pkg").source().directory());
    }

    @Test
    void 目录型Skill白名单引用自带工具之外的未知工具则跳过() throws IOException {
        Path dir = projectSkills().resolve("pkg2");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: pkg2\nmode: inline\ndescription: 目录型坏白名单\nallowedTools: [scan, ghost]\n---\nbody");
        Files.writeString(dir.resolve("tool.json"),
                "{ \"tools\": [ { \"name\": \"scan\", \"description\": \"扫描\", \"script\": \"scan.cmd\" } ] }");
        Files.writeString(dir.resolve("scan.cmd"), "@echo off\necho scan");

        Map<String, SkillDefinition> loaded = loader().loadAll(registryWithKnownTools());
        assertFalse(loaded.containsKey("pkg2"));
    }

    @Test
    void 目录不存在时返回空() {
        Map<String, SkillDefinition> loaded = loader().loadAll(registryWithKnownTools());
        assertNotNull(loaded);
    }

    @Test
    void 内置层加载commitReviewTest() {
        Map<String, SkillDefinition> loaded = loader().loadAll(registryWithKnownTools());
        assertTrue(loaded.containsKey("commit"), "内置 commit 应可加载");
        assertTrue(loaded.containsKey("review"), "内置 review 应可加载");
        assertTrue(loaded.containsKey("test"), "内置 test 应可加载");
        assertEquals(SkillSource.Level.BUILTIN, loaded.get("commit").source().level());
        SkillDefinition review = loaded.get("review");
        assertEquals(SkillMode.FORK, review.mode());
        assertEquals(SkillContextPolicy.ContextType.RECENT, review.context().type());
        assertEquals(20, review.context().recentCount(), "review 的 context 应解析为 recent 20");
    }
}
