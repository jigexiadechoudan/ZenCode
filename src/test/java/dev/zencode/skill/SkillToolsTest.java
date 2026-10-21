package dev.zencode.skill;

import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SkillToolsTest {

    @TempDir
    Path tempDir;

    private SkillRegistry registry;
    private ActiveSkills activeSkills;
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
        activeSkills = new ActiveSkills();
        toolRegistry = ToolRegistry.createDefault(tempDir.resolve("work"));
        SkillSource src = new SkillSource(SkillSource.Level.PROJECT,
                tempDir.resolve("skills").resolve("commit.md"), false);
        SkillDefinition commit = new SkillDefinition("commit", "提交", List.of("Bash", "ReadFile"),
                SkillMode.INLINE, Optional.empty(), SkillContextPolicy.none(), false,
                "SOP $ARGUMENTS", src);
        SkillDefinition open = new SkillDefinition("open", "开放", List.of(),
                SkillMode.INLINE, Optional.empty(), SkillContextPolicy.none(), false,
                "SOP $ARGUMENTS", src);
        registry.replaceAll(Map.of("commit", commit, "open", open));
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(tempDir.resolve("work"), "call-1");
    }

    private ToolCall call(String toolName) {
        return new ToolCall("call-1", toolName, Map.of(), "", "test");
    }

    @Test
    void ActivateSkill激活成功并钉入激活列表() {
        ActivateSkillTool tool = new ActivateSkillTool(registry, activeSkills);
        ToolResult result = tool.execute(Map.of("name", "commit", "arguments", "提交信息"),
                ctx());
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertTrue(activeSkills.names().contains("commit"));
        assertTrue(activeSkills.snapshot().get(0).prompt().contains("提交信息"));
    }

    @Test
    void ActivateSkill未知Skill报错() {
        ActivateSkillTool tool = new ActivateSkillTool(registry, activeSkills);
        ToolResult result = tool.execute(Map.of("name", "ghost"), ctx());
        assertEquals(ToolResult.Status.ERROR, result.status());
        assertTrue(activeSkills.isEmpty());
    }

    @Test
    void LoadSkill激活成功() {
        LoadSkillTool tool = new LoadSkillTool(registry, activeSkills, toolRegistry);
        ToolResult result = tool.execute(Map.of("name", "open"), ctx());
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertTrue(activeSkills.names().contains("open"));
    }

    @Test
    void LoadSkill目录型注册自带工具() throws IOException {
        Path dir = tempDir.resolve("pkg");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: pkg\nmode: inline\ndescription: 目录型\nallowedTools: [scan]\n---\nbody");
        Files.writeString(dir.resolve("tool.json"),
                "{ \"tools\": [ { \"name\": \"scan\", \"description\": \"扫描\", \"script\": \"scan.cmd\" } ] }");
        Files.writeString(dir.resolve("scan.cmd"), "@echo off\necho scan-ok");
        SkillDefinition pkg = new SkillDefinition("pkg", "目录型", List.of("scan"),
                SkillMode.INLINE, Optional.empty(), SkillContextPolicy.none(), false, "body",
                new SkillSource(SkillSource.Level.PROJECT, dir.resolve("SKILL.md"), true));
        registry.replaceAll(Map.of("pkg", pkg));

        LoadSkillTool tool = new LoadSkillTool(registry, activeSkills, toolRegistry);
        ToolResult result = tool.execute(Map.of("name", "pkg"), ctx());
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertTrue(toolRegistry.find("scan").isPresent());
    }

    @Test
    void Whitelist白名单内放行() {
        SkillWhitelistInterceptor interceptor = new SkillWhitelistInterceptor(activeSkills);
        activeSkills.activate(registry.find("commit").get(), null);
        var decision = interceptor.before(call("Bash"), ctx());
        assertTrue(decision instanceof SkillWhitelistInterceptor.BeforeDecision.Allow);
    }

    @Test
    void Whitelist白名单外拒绝() {
        SkillWhitelistInterceptor interceptor = new SkillWhitelistInterceptor(activeSkills);
        activeSkills.activate(registry.find("commit").get(), null);
        var decision = interceptor.before(call("WriteFile"), ctx());
        assertTrue(decision instanceof SkillWhitelistInterceptor.BeforeDecision.Reject);
        ToolResult result = ((SkillWhitelistInterceptor.BeforeDecision.Reject) decision).result();
        assertEquals(ToolResult.Status.RESTRICTED, result.status());
    }

    @Test
    void Whitelist系统工具豁免() {
        SkillWhitelistInterceptor interceptor = new SkillWhitelistInterceptor(activeSkills);
        activeSkills.activate(registry.find("commit").get(), null);
        var decision = interceptor.before(call(SystemToolNames.LOAD_SKILL), ctx());
        assertTrue(decision instanceof SkillWhitelistInterceptor.BeforeDecision.Allow);
    }

    @Test
    void Whitelist空并集全放行() {
        SkillWhitelistInterceptor interceptor = new SkillWhitelistInterceptor(activeSkills);
        activeSkills.activate(registry.find("open").get(), null);
        var decision = interceptor.before(call("WriteFile"), ctx());
        assertTrue(decision instanceof SkillWhitelistInterceptor.BeforeDecision.Allow);
    }
}
