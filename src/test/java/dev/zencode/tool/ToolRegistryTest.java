package dev.zencode.tool;

import dev.zencode.config.Protocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具注册中心测试。
 */
class ToolRegistryTest {
    @TempDir
    Path tempDir;

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = ToolRegistry.createDefault(tempDir);
    }

    @Test
    void defaultRegistryHasSixTools() {
        List<String> names = registry.toolNames();
        assertEquals(6, names.size());
        assertTrue(names.contains("ReadFile"));
        assertTrue(names.contains("WriteFile"));
        assertTrue(names.contains("EditFile"));
        assertTrue(names.contains("Bash"));
        assertTrue(names.contains("Glob"));
        assertTrue(names.contains("Grep"));
    }

    @Test
    void allToolsEnabledByDefault() {
        for (String name : registry.toolNames()) {
            assertTrue(registry.isEnabled(name), name + " 应默认启用");
        }
    }

    @Test
    void canFindToolByName() {
        assertTrue(registry.find("ReadFile").isPresent());
        assertEquals("ReadFile", registry.find("ReadFile").get().name());
    }

    @Test
    void unknownToolReturnsEmpty() {
        assertTrue(registry.find("NoSuchTool").isEmpty());
    }

    @Test
    void disableAndEnableWork() {
        assertTrue(registry.isEnabled("Glob"));
        registry.disable("Glob");
        assertFalse(registry.isEnabled("Glob"));
        registry.enable("Glob");
        assertTrue(registry.isEnabled("Glob"));
    }

    @Test
    void disabledToolAbsentFromSchema() {
        registry.disable("WriteFile");
        List<Map<String, Object>> schemas = registry.schemas(Protocol.ANTHROPIC);
        for (Map<String, Object> s : schemas) {
            assertNotEquals("WriteFile", s.get("name"), "禁用工具不应出现在 Schema 中");
        }
    }

    @Test
    void disabledToolExecutionReturnsDisabled() {
        registry.disable("Glob");
        ToolExecutionContext ctx = new ToolExecutionContext(tempDir, "call-1");
        ToolResult result = registry.execute("Glob", Map.of("pattern", "*.txt"), ctx);
        assertFalse(result.success());
        assertEquals(ToolResult.Status.DISABLED, result.status());
    }

    @Test
    void unknownToolExecutionReturnsError() {
        ToolExecutionContext ctx = new ToolExecutionContext(tempDir, "call-1");
        ToolResult result = registry.execute("NoSuchTool", Map.of(), ctx);
        assertFalse(result.success());
        assertEquals(ToolResult.Status.ERROR, result.status());
        assertTrue(result.content().contains("NoSuchTool"));
    }

    @Test
    void sameNameOverridePreservesOrder() {
        Tool original = registry.find("Glob").get();
        // 注册同名工具
        Tool override = new BaseTool("Glob", "覆盖版本", ToolCategory.READ, ToolPermission.READ_ONLY, Map.of()) {
            @Override
            protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
                return ToolResult.success(ctx.callId(), "Glob", "覆盖");
            }
        };
        registry.register(override);
        assertTrue(registry.find("Glob").isPresent());
        // 检查顺序中 Glob 仍在原位置
        List<String> names = registry.toolNames();
        assertEquals("Glob", names.get(4)); // ReadFile, WriteFile, EditFile, Bash, Glob, Grep
    }

    @Test
    void unregisterRemovesToolAndDisablesIt() {
        registry.unregister("Glob");
        assertFalse(registry.toolNames().contains("Glob"));
        assertFalse(registry.isEnabled("Glob"));
        assertTrue(registry.find("Glob").isEmpty());
        ToolExecutionContext ctx = new ToolExecutionContext(tempDir, "call-1");
        ToolResult result = registry.execute("Glob", Map.of("pattern", "*.txt"), ctx);
        assertFalse(result.success());
        assertEquals(ToolResult.Status.ERROR, result.status());
    }

    @Test
    void unregisterUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> registry.unregister("NoSuchTool"));
    }

    @Test
    void unregisterThenRegisterRestoresTool() {
        registry.unregister("Glob");
        Tool replacement = new BaseTool("Glob", "重建版本", ToolCategory.READ, ToolPermission.READ_ONLY, Map.of()) {
            @Override
            protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
                return ToolResult.success(ctx.callId(), "Glob", "重建");
            }
        };
        registry.register(replacement);
        assertTrue(registry.isEnabled("Glob"));
        assertEquals("重建版本", registry.find("Glob").get().description());
    }

    @Test
    void anthropicSchemaHasCorrectStructure() {
        List<Map<String, Object>> schemas = registry.schemas(Protocol.ANTHROPIC);
        assertEquals(6, schemas.size());
        for (Map<String, Object> s : schemas) {
            assertTrue(s.containsKey("name"));
            assertTrue(s.containsKey("description"));
            assertTrue(s.containsKey("input_schema"));
        }
    }

    @Test
    void openaiSchemaHasCorrectStructure() {
        List<Map<String, Object>> schemas = registry.schemas(Protocol.OPENAI);
        assertEquals(6, schemas.size());
        for (Map<String, Object> s : schemas) {
            assertEquals("function", s.get("type"));
            assertTrue(s.containsKey("function"));
            Map<String, Object> fn = (Map<String, Object>) s.get("function");
            assertTrue(fn.containsKey("name"));
            assertTrue(fn.containsKey("description"));
            assertTrue(fn.containsKey("parameters"));
        }
    }

    @Test
    void schemaOrderIsStable() {
        List<Map<String, Object>> first = registry.schemas(Protocol.ANTHROPIC);
        List<Map<String, Object>> second = registry.schemas(Protocol.ANTHROPIC);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).get("name"), second.get(i).get("name"));
        }
    }

    @Test
    void toolsHaveCorrectCategory() {
        assertEquals(ToolCategory.READ, registry.find("ReadFile").get().category());
        assertEquals(ToolCategory.WRITE, registry.find("WriteFile").get().category());
        assertEquals(ToolCategory.WRITE, registry.find("EditFile").get().category());
        assertEquals(ToolCategory.COMMAND, registry.find("Bash").get().category());
        assertEquals(ToolCategory.READ, registry.find("Glob").get().category());
        assertEquals(ToolCategory.READ, registry.find("Grep").get().category());
    }

    @Test
    void toolsHaveCorrectPermission() {
        assertEquals(ToolPermission.READ_ONLY, registry.find("ReadFile").get().permission());
        assertEquals(ToolPermission.REQUIRES_CONFIRMATION, registry.find("WriteFile").get().permission());
        assertEquals(ToolPermission.REQUIRES_CONFIRMATION, registry.find("EditFile").get().permission());
        assertEquals(ToolPermission.REQUIRES_CONFIRMATION, registry.find("Bash").get().permission());
        assertEquals(ToolPermission.READ_ONLY, registry.find("Glob").get().permission());
        assertEquals(ToolPermission.READ_ONLY, registry.find("Grep").get().permission());
    }
}