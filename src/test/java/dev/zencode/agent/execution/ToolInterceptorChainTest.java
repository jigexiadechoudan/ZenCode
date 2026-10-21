package dev.zencode.agent.execution;

import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T11：工具拦截器链测试。覆盖调用顺序、默认放行、before 拒绝不执行、
 * after 可观察结果、异常安全转换和 plan-only 分类。
 */
class ToolInterceptorChainTest {

    private static ToolCall call(String id, String name) {
        return new ToolCall(id, name, Map.of(), "{}", "anthropic");
    }

    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(Path.of("."), callId);
    }

    @Test
    void emptyChainAllowsByDefault() {
        ToolInterceptorChain chain = ToolInterceptorChain.empty();
        ToolCall c = call("call-1", "ReadFile");
        ToolExecutionContext ctx = context("call-1");
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                chain.before(c, ctx));
        ToolResult result = ToolResult.success("call-1", "ReadFile", "内容");
        assertEquals(result, chain.after(c, result, ctx));
    }

    @Test
    void interceptorsRunInRegistrationOrder() {
        AtomicInteger order = new AtomicInteger();
        ToolExecutionInterceptor first = new RecordingInterceptor("first", order);
        ToolExecutionInterceptor second = new RecordingInterceptor("second", order);
        ToolInterceptorChain chain = new ToolInterceptorChain(List.of(first, second));

        ToolCall c = call("call-1", "ReadFile");
        chain.before(c, context("call-1"));
        chain.after(c, ToolResult.success("call-1", "ReadFile", "内容"), context("call-1"));

        assertEquals(4, order.get());
    }

    @Test
    void beforeRejectShortCircuitsAndStopsExecution() {
        AtomicInteger beforeCount = new AtomicInteger();
        ToolExecutionInterceptor rejecting = new ToolExecutionInterceptor() {
            @Override
            public ToolExecutionInterceptor.BeforeDecision before(ToolCall call, ToolExecutionContext context) {
                beforeCount.incrementAndGet();
                return new ToolExecutionInterceptor.BeforeDecision.Reject(
                        ToolResult.restricted(call.callId(), call.toolName(), "受限"));
            }

            @Override
            public ToolResult after(ToolCall call, ToolResult result, ToolExecutionContext context) {
                return result;
            }
        };
        ToolInterceptorChain chain = new ToolInterceptorChain(
                List.of(rejecting, new RecordingInterceptor("later", new AtomicInteger())));

        ToolCall c = call("call-1", "WriteFile");
        ToolExecutionInterceptor.BeforeDecision decision = chain.before(c, context("call-1"));
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, decision);
        ToolResult result = ((ToolExecutionInterceptor.BeforeDecision.Reject) decision).result();
        assertEquals(ToolResult.Status.RESTRICTED, result.status());
        assertEquals("call-1", result.callId());
        assertEquals(1, beforeCount.get());
    }

    @Test
    void afterCanObserveResult() {
        AtomicInteger observed = new AtomicInteger();
        ToolExecutionInterceptor observer = new ToolExecutionInterceptor() {
            @Override
            public ToolExecutionInterceptor.BeforeDecision before(ToolCall call, ToolExecutionContext context) {
                return new ToolExecutionInterceptor.BeforeDecision.Allow();
            }

            @Override
            public ToolResult after(ToolCall call, ToolResult result, ToolExecutionContext context) {
                observed.set(result.success() ? 1 : 0);
                return result;
            }
        };
        ToolInterceptorChain chain = new ToolInterceptorChain(List.of(observer));

        ToolCall c = call("call-1", "ReadFile");
        ToolResult result = ToolResult.success("call-1", "ReadFile", "内容");
        assertEquals(result, chain.after(c, result, context("call-1")));
        assertEquals(1, observed.get());
    }

    @Test
    void beforeExceptionConvertsToSafeFailure() {
        ToolExecutionInterceptor throwing = new ToolExecutionInterceptor() {
            @Override
            public ToolExecutionInterceptor.BeforeDecision before(ToolCall call, ToolExecutionContext context) {
                throw new IllegalStateException("内部细节不应暴露");
            }

            @Override
            public ToolResult after(ToolCall call, ToolResult result, ToolExecutionContext context) {
                return result;
            }
        };
        ToolInterceptorChain chain = new ToolInterceptorChain(List.of(throwing));

        ToolCall c = call("call-1", "ReadFile");
        ToolExecutionInterceptor.BeforeDecision decision = chain.before(c, context("call-1"));
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, decision);
        ToolResult result = ((ToolExecutionInterceptor.BeforeDecision.Reject) decision).result();
        assertFalse(result.success());
        assertFalse(result.content().contains("内部细节"));
    }

    @Test
    void afterExceptionConvertsToSafeFailure() {
        ToolExecutionInterceptor throwing = new ToolExecutionInterceptor() {
            @Override
            public ToolExecutionInterceptor.BeforeDecision before(ToolCall call, ToolExecutionContext context) {
                return new ToolExecutionInterceptor.BeforeDecision.Allow();
            }

            @Override
            public ToolResult after(ToolCall call, ToolResult result, ToolExecutionContext context) {
                throw new IllegalStateException("堆栈信息不应进入结果");
            }
        };
        ToolInterceptorChain chain = new ToolInterceptorChain(List.of(throwing));

        ToolCall c = call("call-1", "ReadFile");
        ToolResult result = chain.after(c, ToolResult.success("call-1", "ReadFile", "内容"), context("call-1"));
        assertFalse(result.success());
        assertFalse(result.content().contains("堆栈信息"));
        assertFalse(result.content().contains("IllegalStateException"));
    }

    // ---- PlanOnlyInterceptor ----

    @Test
    void planOnlyAllowsReadTools() {
        PlanOnlyInterceptor interceptor = new PlanOnlyInterceptor(name -> ToolCategory.READ);
        ToolCall c = call("call-1", "ReadFile");
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(c, context("call-1")));
    }

    @Test
    void planOnlyRejectsWriteAndCommandWithRestricted() {
        PlanOnlyInterceptor interceptor = new PlanOnlyInterceptor(name -> ToolCategory.WRITE);
        ToolCall write = call("call-w", "WriteFile");
        ToolExecutionInterceptor.BeforeDecision writeDecision = interceptor.before(write, context("call-w"));
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, writeDecision);
        ToolResult writeResult = ((ToolExecutionInterceptor.BeforeDecision.Reject) writeDecision).result();
        assertEquals(ToolResult.Status.RESTRICTED, writeResult.status());
        assertEquals("call-w", writeResult.callId());
        assertEquals("WriteFile", writeResult.toolName());

        PlanOnlyInterceptor commandInterceptor = new PlanOnlyInterceptor(name -> ToolCategory.COMMAND);
        ToolCall command = call("call-c", "Bash");
        ToolExecutionInterceptor.BeforeDecision commandDecision = commandInterceptor.before(command, context("call-c"));
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, commandDecision);
        assertEquals(ToolResult.Status.RESTRICTED,
                ((ToolExecutionInterceptor.BeforeDecision.Reject) commandDecision).result().status());
    }

    @Test
    void planOnlyRejectsUnknownTool() {
        PlanOnlyInterceptor interceptor = new PlanOnlyInterceptor(name -> null);
        ToolCall c = call("call-1", "UnknownTool");
        ToolExecutionInterceptor.BeforeDecision decision = interceptor.before(c, context("call-1"));
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, decision);
    }

    @Test
    void planOnlyDelegatesOnlyExplicitReadOnlyRoles() {
        PlanOnlyInterceptor interceptor = new PlanOnlyInterceptor(name -> ToolCategory.COMMAND);
        ToolCall explore = new ToolCall("e", "agent",
                java.util.Map.of("task", "inspect", "subagent_type", "explore"), "", "openai");
        ToolCall general = new ToolCall("g", "agent",
                java.util.Map.of("task", "modify", "subagent_type", "general-purpose"), "", "openai");
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(explore, context("e")));
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class,
                interceptor.before(general, context("g")));
    }

    @Test
    void planOnlyAfterReturnsResultUnchanged() {
        PlanOnlyInterceptor interceptor = new PlanOnlyInterceptor(name -> ToolCategory.READ);
        ToolCall c = call("call-1", "ReadFile");
        ToolResult result = ToolResult.success("call-1", "ReadFile", "内容");
        assertEquals(result, interceptor.after(c, result, context("call-1")));
    }

    private static final class RecordingInterceptor implements ToolExecutionInterceptor {
        private final String name;
        private final AtomicInteger order;

        RecordingInterceptor(String name, AtomicInteger order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public ToolExecutionInterceptor.BeforeDecision before(ToolCall call, ToolExecutionContext context) {
            order.incrementAndGet();
            return new ToolExecutionInterceptor.BeforeDecision.Allow();
        }

        @Override
        public ToolResult after(ToolCall call, ToolResult result, ToolExecutionContext context) {
            order.incrementAndGet();
            return result;
        }
    }
}
