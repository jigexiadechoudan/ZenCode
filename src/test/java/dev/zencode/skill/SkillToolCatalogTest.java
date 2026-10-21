package dev.zencode.skill;

import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 目录型 Skill 工具测试：tool.json 解析、脚本执行、缺失文件返回空。
 */
class SkillToolCatalogTest {

    @TempDir
    Path tempDir;

    private Path skillDir(String name) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void 解析toolJson得到spec() throws IOException {
        Path dir = skillDir("pkg");
        Files.writeString(dir.resolve("tool.json"),
                "{ \"tools\": [ { \"name\": \"scan\", \"description\": \"扫描\", "
                        + "\"schema\": { \"type\": \"object\", \"properties\": { \"path\": {\"type\": \"string\"} } }, "
                        + "\"script\": \"scan.cmd\" } ] }");
        Files.writeString(dir.resolve("scan.cmd"), "@echo off\nrem placeholder");

        List<SkillToolSpec> specs = SkillToolCatalog.parse(dir);
        assertEquals(1, specs.size());
        SkillToolSpec spec = specs.get(0);
        assertEquals("scan", spec.name());
        assertEquals("扫描", spec.description());
        assertEquals(ScriptKind.CMD, spec.kind());
        assertEquals(dir.resolve("scan.cmd").normalize(), spec.script().normalize());
    }

    @Test
    void 目录不存在或无toolJson返回空() {
        Path missing = tempDir.resolve("nope");
        assertTrue(SkillToolCatalog.parse(missing).isEmpty(), "目录不存在应返回空");
        assertTrue(SkillToolCatalog.parse(tempDir).isEmpty(), "无 tool.json 应返回空");
    }

    @Test
    void cmd脚本执行返回stdout() throws IOException {
        Path dir = skillDir("runpkg");
        Files.writeString(dir.resolve("tool.json"),
                "{ \"tools\": [ { \"name\": \"hello\", \"description\": \"你好\", \"script\": \"hello.cmd\" } ] }");
        Files.writeString(dir.resolve("hello.cmd"),
                "@echo off\r\nsetlocal\r\nif exist \"%ZENCODE_SKILL_ARGS%\" type \"%ZENCODE_SKILL_ARGS%\" >nul\r\necho hello-world\r\nexit /b 0");

        ToolRegistry registry = ToolRegistry.createDefault(tempDir);
        SkillToolCatalog.registerTools(dir, registry);

        assertTrue(registry.find("hello").isPresent(), "工具应已注册");
        ToolResult result = registry.execute("hello", java.util.Map.of("x", "1"),
                new ToolExecutionContext(tempDir, "call-1"));
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertTrue(result.content().contains("hello-world"), "应输出脚本 stdout");
    }

    @Test
    void 脚本退出码非零返回error() throws IOException {
        Path dir = skillDir("failpkg");
        Files.writeString(dir.resolve("tool.json"),
                "{ \"tools\": [ { \"name\": \"boom\", \"description\": \"失败\", \"script\": \"boom.cmd\" } ] }");
        Files.writeString(dir.resolve("boom.cmd"), "@echo off\r\necho something-went-wrong\r\nexit /b 3");

        ToolRegistry registry = ToolRegistry.createDefault(tempDir);
        SkillToolCatalog.registerTools(dir, registry);
        ToolResult result = registry.execute("boom", java.util.Map.of(),
                new ToolExecutionContext(tempDir, "call-1"));
        assertEquals(ToolResult.Status.ERROR, result.status());
        assertTrue(result.content().contains("something-went-wrong"), "应携带输出内容");
    }
}
