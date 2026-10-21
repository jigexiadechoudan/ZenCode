package dev.zencode.tool;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具核心类型测试：分类、权限、结果工厂、执行上下文和基类参数校验。
 */
class ToolCoreTest {

    // ---- ToolCategory ----

    @Test
    void toolCategoryHasThreeValues() {
        assertEquals(3, ToolCategory.values().length);
        assertEquals(ToolCategory.READ, ToolCategory.valueOf("READ"));
        assertEquals(ToolCategory.WRITE, ToolCategory.valueOf("WRITE"));
        assertEquals(ToolCategory.COMMAND, ToolCategory.valueOf("COMMAND"));
    }

    // ---- ToolPermission ----

    @Test
    void toolPermissionHasTwoValues() {
        assertEquals(2, ToolPermission.values().length);
        assertEquals(ToolPermission.READ_ONLY, ToolPermission.valueOf("READ_ONLY"));
        assertEquals(ToolPermission.REQUIRES_CONFIRMATION, ToolPermission.valueOf("REQUIRES_CONFIRMATION"));
    }

    // ---- ToolResult 工厂方法 ----

    @Test
    void successResultHasCorrectFields() {
        ToolResult result = ToolResult.success("call-1", "test-tool", "操作成功");
        assertEquals("call-1", result.callId());
        assertEquals("test-tool", result.toolName());
        assertTrue(result.success());
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertEquals("操作成功", result.content());
        assertFalse(result.truncated());
    }

    @Test
    void truncatedResultMarksTruncated() {
        ToolResult result = ToolResult.truncated("call-2", "test-tool", "部分内容");
        assertTrue(result.success());
        assertEquals(ToolResult.Status.TRUNCATED, result.status());
        assertTrue(result.truncated());
    }

    @Test
    void errorResultHasCorrectState() {
        ToolResult result = ToolResult.error("call-3", "test-tool", "发生错误");
        assertFalse(result.success());
        assertEquals(ToolResult.Status.ERROR, result.status());
    }

    @Test
    void invalidArgumentDoesNotExecute() {
        ToolResult result = ToolResult.invalidArgument("call-4", "test-tool", "参数缺失");
        assertFalse(result.success());
        assertEquals(ToolResult.Status.INVALID_ARGUMENT, result.status());
    }

    @Test
    void disabledResultHasCorrectState() {
        ToolResult result = ToolResult.disabled("call-5", "disabled-tool");
        assertFalse(result.success());
        assertEquals(ToolResult.Status.DISABLED, result.status());
        assertTrue(result.content().contains("disabled-tool"));
    }

    @Test
    void timeoutResultHasCorrectState() {
        ToolResult result = ToolResult.timeout("call-6", "test-tool", "超时");
        assertFalse(result.success());
        assertEquals(ToolResult.Status.TIMEOUT, result.status());
    }

    @Test
    void cancelledResultHasCorrectState() {
        ToolResult result = ToolResult.cancelled("call-7", "test-tool", "已取消");
        assertFalse(result.success());
        assertEquals(ToolResult.Status.CANCELLED, result.status());
    }

    @Test
    void unknownToolResultHasErrorState() {
        ToolResult result = ToolResult.unknownTool("call-8", "no-such-tool");
        assertFalse(result.success());
        assertEquals(ToolResult.Status.ERROR, result.status());
        assertTrue(result.content().contains("no-such-tool"));
    }

    @Test
    void restrictedResultHasCorrectState() {
        ToolResult result = ToolResult.restricted("call-r", "WriteFile", "plan-only 禁止写入");
        assertFalse(result.success());
        assertEquals(ToolResult.Status.RESTRICTED, result.status());
        assertTrue(result.content().contains("plan-only"));
    }

    @Test
    void resultContentDefaultsToEmpty() {
        // 通过反射测试 content 为 null 时的默认行为
        ToolResult r = ToolResult.success("c", "t", null);
        assertEquals("", r.content());
    }

    @Test
    void resultEqualityAndHashCode() {
        ToolResult a = ToolResult.success("c1", "t1", "ok");
        ToolResult b = ToolResult.success("c1", "t1", "ok");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ---- ToolExecutionContext ----

    @Test
    void executionContextProvidesFields() {
        ToolExecutionContext ctx = new ToolExecutionContext(java.nio.file.Path.of("/work"), "call-1");
        assertEquals(java.nio.file.Path.of("/work"), ctx.workingDirectory());
        assertEquals("call-1", ctx.callId());
        assertFalse(ctx.isCancelled());
    }

    @Test
    void executionContextCancelWorks() {
        ToolExecutionContext ctx = new ToolExecutionContext(java.nio.file.Path.of("/work"), "call-2");
        assertFalse(ctx.isCancelled());
        ctx.cancel();
        assertTrue(ctx.isCancelled());
        // 重复取消安全
        ctx.cancel();
        assertTrue(ctx.isCancelled());
    }

    @Test
    void executionContextSharesCancellationSignal() {
        java.util.concurrent.atomic.AtomicBoolean signal = new java.util.concurrent.atomic.AtomicBoolean(false);
        ToolExecutionContext first = new ToolExecutionContext(java.nio.file.Path.of("/work"), "call-a", signal);
        ToolExecutionContext second = new ToolExecutionContext(java.nio.file.Path.of("/work"), "call-b", signal);
        assertEquals("call-a", first.callId());
        assertEquals("call-b", second.callId());
        assertFalse(first.isCancelled());
        assertFalse(second.isCancelled());
        first.cancel();
        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled(), "共享取消信号应传播到同一运行的所有上下文");
    }

    // ---- BaseTool 参数校验 ----

    @Test
    void baseToolRequiredStringReturnsValue() {
        RecordingTool tool = new RecordingTool();
        assertEquals("hello", tool.callRequiredString(Map.of("key", "hello"), "key"));
    }

    @Test
    void baseToolRequiredStringRejectsMissing() {
        RecordingTool tool = new RecordingTool();
        assertThrows(BaseTool.ToolExecutionException.class,
                () -> tool.callRequiredString(Map.of(), "key"));
    }

    @Test
    void baseToolRequiredStringRejectsWrongType() {
        RecordingTool tool = new RecordingTool();
        assertThrows(BaseTool.ToolExecutionException.class,
                () -> tool.callRequiredString(Map.of("key", 123), "key"));
    }

    @Test
    void baseToolRequiredIntReturnsValue() {
        RecordingTool tool = new RecordingTool();
        assertEquals(42, tool.callRequiredInt(Map.of("key", 42), "key"));
    }

    @Test
    void baseToolRequiredIntParsesString() {
        RecordingTool tool = new RecordingTool();
        assertEquals(42, tool.callRequiredInt(Map.of("key", "42"), "key"));
    }

    @Test
    void baseToolRequiredIntRejectsMissing() {
        RecordingTool tool = new RecordingTool();
        assertThrows(BaseTool.ToolExecutionException.class,
                () -> tool.callRequiredInt(Map.of(), "key"));
    }

    @Test
    void baseToolOptionalStringReturnsDefault() {
        RecordingTool tool = new RecordingTool();
        assertEquals("def", tool.callOptionalString(Map.of(), "key", "def"));
    }

    @Test
    void baseToolOptionalIntReturnsDefault() {
        RecordingTool tool = new RecordingTool();
        assertEquals(10, tool.callOptionalInt(Map.of(), "key", 10));
    }

    @Test
    void baseToolExecuteCatchesException() {
        RecordingTool tool = new RecordingTool();
        ToolExecutionContext ctx = new ToolExecutionContext(java.nio.file.Path.of("/"), "c1");
        ToolResult result = tool.execute(Map.of("fail", true), ctx);
        assertFalse(result.success());
        assertEquals(ToolResult.Status.ERROR, result.status());
    }

    @Test
    void baseToolExecuteSuccess() {
        RecordingTool tool = new RecordingTool();
        ToolExecutionContext ctx = new ToolExecutionContext(java.nio.file.Path.of("/"), "c1");
        ToolResult result = tool.execute(Map.of(), ctx);
        assertTrue(result.success());
        assertEquals("ok", result.content());
    }

    /** 用于测试 BaseTool 参数辅助方法的工具实现。 */
    static final class RecordingTool extends BaseTool {
        RecordingTool() {
            super("recorder", "测试工具", ToolCategory.READ, ToolPermission.READ_ONLY, Map.of());
        }

        @Override
        protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
            if (args.containsKey("fail")) throw new ToolExecutionException("模拟失败");
            return ToolResult.success(ctx.callId(), name(), "ok");
        }

        // 暴露给测试的辅助方法
        String callRequiredString(Map<String, Object> args, String key) { return requiredString(args, key); }
        int callRequiredInt(Map<String, Object> args, String key) { return requiredInt(args, key); }
        String callOptionalString(Map<String, Object> args, String key, String def) { return optionalString(args, key, def); }
        int callOptionalInt(Map<String, Object> args, String key, int def) { return optionalInt(args, key, def); }
    }
}