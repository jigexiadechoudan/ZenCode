package dev.zencode.hook;

import dev.zencode.agent.execution.ToolExecutionInterceptor;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HookToolInterceptorTest {

    private ToolCall call(String tool, Map<String, Object> args) {
        return new ToolCall("call-1", tool, args, "", "test");
    }

    @Test
    void reject转换为受限工具结果() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(new Hook("block", HookEvent.PRE_TOOL_USE,
                ConditionParser.parse("tool == \"Bash\""),
                new PromptAction("拦截原因"), false, false, true)));
        HookToolInterceptor interceptor = new HookToolInterceptor(engine);
        ToolCall toolCall = call("Bash", Map.of("command", "rm -rf /"));
        ToolExecutionContext context = new ToolExecutionContext(Path.of("."), "call-1");
        ToolExecutionInterceptor.BeforeDecision decision = interceptor.before(toolCall, context);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, decision);
        ToolResult result = ((ToolExecutionInterceptor.BeforeDecision.Reject) decision).result();
        assertFalse(result.success());
        assertTrue(result.content().contains("拦截原因"), result.content());
    }

    @Test
    void 未命中拒绝放行() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(new Hook("block", HookEvent.PRE_TOOL_USE,
                ConditionParser.parse("tool == \"Bash\""),
                new PromptAction("x"), false, false, true)));
        HookToolInterceptor interceptor = new HookToolInterceptor(engine);
        ToolCall toolCall = call("Glob", Map.of("path", "**/*.java"));
        ToolExecutionContext context = new ToolExecutionContext(Path.of("."), "call-1");
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(toolCall, context));
    }

    @Test
    void after触发postToolUse并注入上下文() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(new Hook("p", HookEvent.POST_TOOL_USE,
                ConditionParser.parse("tool == \"WriteFile\""),
                new PromptAction("已写文件 $FILE_PATH"), false, false, false)));
        HookToolInterceptor interceptor = new HookToolInterceptor(engine);
        ToolCall toolCall = call("WriteFile", Map.of("path", "src\\main.py"));
        ToolExecutionContext context = new ToolExecutionContext(Path.of("."), "call-1");
        interceptor.after(toolCall, ToolResult.success("call-1", "WriteFile", "ok"), context);
        assertEquals(List.of("已写文件 src\\main.py"), engine.injectedPrompts());
    }

    @Test
    void 写文件成功触发postToolUse和fileChange() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(
                new Hook("p", HookEvent.POST_TOOL_USE, null, new PromptAction("post"), false, false, false),
                new Hook("f", HookEvent.FILE_CHANGE, null, new PromptAction("file-changed"), false, false, false)));
        HookToolInterceptor interceptor = new HookToolInterceptor(engine);
        ToolCall toolCall = call("WriteFile", Map.of("path", "a.txt"));
        ToolExecutionContext context = new ToolExecutionContext(Path.of("."), "call-1");
        interceptor.after(toolCall, ToolResult.success("call-1", "WriteFile", "ok"), context);
        assertTrue(engine.injectedPrompts().containsAll(List.of("post", "file-changed")),
                engine.injectedPrompts().toString());
    }

    @Test
    void 非写文件只触发postToolUse() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(
                new Hook("p", HookEvent.POST_TOOL_USE, null, new PromptAction("post"), false, false, false),
                new Hook("f", HookEvent.FILE_CHANGE, null, new PromptAction("file-changed"), false, false, false)));
        HookToolInterceptor interceptor = new HookToolInterceptor(engine);
        ToolCall toolCall = call("Bash", Map.of("command", "ls"));
        ToolExecutionContext context = new ToolExecutionContext(Path.of("."), "call-1");
        interceptor.after(toolCall, ToolResult.success("call-1", "Bash", "out"), context);
        assertEquals(List.of("post"), engine.injectedPrompts());
    }

    @Test
    void 写文件失败只触发postToolUse带错误信息() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(new Hook("p", HookEvent.POST_TOOL_USE,
                ConditionParser.parse("error != \"\""),
                new PromptAction("写文件失败: $ERROR"), false, false, false)));
        HookToolInterceptor interceptor = new HookToolInterceptor(engine);
        ToolCall toolCall = call("EditFile", Map.of("path", "a.txt"));
        ToolExecutionContext context = new ToolExecutionContext(Path.of("."), "call-1");
        interceptor.after(toolCall, ToolResult.error("call-1", "EditFile", "文件不存在"), context);
        assertEquals(List.of("写文件失败: 文件不存在"), engine.injectedPrompts());
    }
}
