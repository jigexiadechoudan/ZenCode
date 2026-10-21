package dev.zencode.hook;

import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HookEngineTest {

    private static Hook hook(String id, HookEvent event, String message, boolean once, boolean reject) {
        return new Hook(id, event, null, new PromptAction(message), once, false, reject);
    }

    private HookContext ctx() {
        return new HookContext("session_start", null, Map.of(), null, "hello", null);
    }

    @Test
    void 事件过滤只触发匹配事件() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(hook("a", HookEvent.SESSION_START, "hi", false, false)));
        engine.runHooks(HookEvent.SESSION_START, ctx());
        engine.runHooks(HookEvent.TURN_START, ctx());
        assertEquals(List.of("hi"), engine.injectedPrompts());
    }

    @Test
    void 条件过滤不匹配不触发() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(new Hook("a", HookEvent.SESSION_START,
                ConditionParser.parse("message == \"nope\""),
                new PromptAction("hi"), false, false, false)));
        engine.runHooks(HookEvent.SESSION_START, ctx());
        assertTrue(engine.injectedPrompts().isEmpty());
    }

    @Test
    void once只执行一次() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(hook("a", HookEvent.SESSION_START, "once-msg", true, false)));
        engine.runHooks(HookEvent.SESSION_START, ctx());
        engine.runHooks(HookEvent.SESSION_START, ctx());
        assertEquals(1, engine.injectedPrompts().size());
    }

    @Test
    void async后台执行() throws Exception {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(hook("a", HookEvent.SESSION_START, "async-msg", false, false)));
        engine.runHooks(HookEvent.SESSION_START, ctx());
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (engine.injectedPrompts().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(List.of("async-msg"), engine.injectedPrompts());
    }

    @Test
    void 命令失败只记日志不抛() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(new Hook("a", HookEvent.SESSION_START, null,
                new CommandAction("exit /b 1", Duration.ofSeconds(5)), false, false, false)));
        assertDoesNotThrow(() -> engine.runHooks(HookEvent.SESSION_START, ctx()));
    }

    @Test
    void prompt注入去重() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(
                hook("a", HookEvent.SESSION_START, "same", false, false),
                hook("b", HookEvent.SESSION_START, "same", false, false)));
        engine.runHooks(HookEvent.SESSION_START, ctx());
        assertEquals(1, engine.injectedPrompts().size());
    }

    @Test
    void reject短路后续不执行() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(
                hook("block", HookEvent.PRE_TOOL_USE, "blocked", false, true),
                hook("after", HookEvent.PRE_TOOL_USE, "after-msg", false, false)));
        ToolCall call = new ToolCall("call-1", "Bash", Map.of("command", "rm -rf /"), "", "test");
        ToolExecutionContext context = new ToolExecutionContext(Path.of("."), "call-1");
        Optional<ToolRejectedError> result = engine.runPreToolHooks(call, context);
        assertTrue(result.isPresent());
        assertEquals("blocked", result.get().reason());
        assertTrue(engine.injectedPrompts().isEmpty(), "短路后不应执行后续 prompt hook");
    }

    @Test
    void 非reject的preToolUse放行() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(hook("a", HookEvent.PRE_TOOL_USE, "notify", false, false)));
        ToolCall call = new ToolCall("call-1", "Bash", Map.of("command", "ls"), "", "test");
        ToolExecutionContext context = new ToolExecutionContext(Path.of("."), "call-1");
        assertEquals(Optional.empty(), engine.runPreToolHooks(call, context));
        assertEquals(List.of("notify"), engine.injectedPrompts());
    }

    @Test
    void 条件匹配的reject返回动作输出原因() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(new Hook("block", HookEvent.PRE_TOOL_USE,
                ConditionParser.parse("tool == \"Bash\" && args.command =~ \"rm -rf\""),
                new PromptAction("危险命令被拦截: $TOOL_ARGS.command"), false, false, true)));
        ToolCall call = new ToolCall("call-1", "Bash", Map.of("command", "rm -rf /"), "", "test");
        ToolExecutionContext context = new ToolExecutionContext(Path.of("."), "call-1");
        Optional<ToolRejectedError> result = engine.runPreToolHooks(call, context);
        assertTrue(result.isPresent());
        assertTrue(result.get().reason().contains("危险命令被拦截"), result.get().reason());
    }

    @Test
    void reset清空once与注入() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(hook("a", HookEvent.SESSION_START, "x", true, false)));
        engine.runHooks(HookEvent.SESSION_START, ctx());
        assertEquals(1, engine.injectedPrompts().size());
        engine.reset();
        assertTrue(engine.injectedPrompts().isEmpty());
        engine.runHooks(HookEvent.SESSION_START, ctx());
        assertEquals(1, engine.injectedPrompts().size(), "reset 后 once 重新生效");
    }
}
