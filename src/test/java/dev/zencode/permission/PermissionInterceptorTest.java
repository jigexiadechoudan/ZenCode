package dev.zencode.permission;

import dev.zencode.agent.execution.ToolExecutionInterceptor;
import dev.zencode.permission.hitl.HitlBroker;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.rule.Rule;
import dev.zencode.permission.rule.RuleAction;
import dev.zencode.permission.rule.RuleEngine;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.WorkspacePathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionInterceptorTest {

    @TempDir
    Path tempDir;

    private Path projectRules;
    private Path userRules;
    private ToolRegistry registry;
    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        Path ws = Files.createDirectories(tempDir.resolve("ws"));
        projectRules = Files.createDirectories(tempDir.resolve("proj"))
                .resolve("rules.yaml");
        userRules = Files.createDirectories(tempDir.resolve("user"))
                .resolve("rules.yaml");
        Files.writeString(projectRules, "rules: []\n");
        Files.writeString(userRules, "rules: []\n");
        registry = ToolRegistry.createDefault(ws);
        ctx = new ToolExecutionContext(ws, "call-1");
    }

    private PermissionInterceptor interceptor(PermissionMode mode, HitlBroker broker) {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir.resolve("ws"));
        RuleEngine ruleEngine = new RuleEngine(projectRules, userRules);
        PermissionChecker checker = new PermissionChecker(new DangerCommandDetector(),
                new PathSandbox(resolver), ruleEngine, mode, registry);
        return new PermissionInterceptor(checker, ruleEngine, broker);
    }

    private ToolCall bash(String command) {
        return new ToolCall("call-1", "Bash", Map.of("command", command), "", "openai");
    }

    private ToolCall writeFile(String path) {
        return new ToolCall("call-1", "WriteFile", Map.of("path", path, "content", "x"), "", "openai");
    }

    @Test
    void dangerousCommandHardBlockedWithoutHitl() {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.TRUST_ALL, broker);
        var decision = interceptor.before(bash("del /s /q C:\\"), ctx);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, decision);
        ToolResult result = ((ToolExecutionInterceptor.BeforeDecision.Reject) decision).result();
        assertEquals(ToolResult.Status.RESTRICTED, result.status());
        assertEquals(0, broker.pendingCount());
    }

    @Test
    void pathEscapeHardBlockedWithoutHitl() {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.TRUST_ALL, broker);
        var decision = interceptor.before(writeFile("../../outside.txt"), ctx);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, decision);
        ToolResult result = ((ToolExecutionInterceptor.BeforeDecision.Reject) decision).result();
        assertEquals(ToolResult.Status.RESTRICTED, result.status());
        assertEquals(0, broker.pendingCount());
    }

    @Test
    void projectRuleAllowPermits() throws Exception {
        Files.writeString(projectRules, "rules:\n  - tool: Bash\n    pattern: \"git push *\"\n    action: allow\n");
        PermissionInterceptor interceptor = interceptor(PermissionMode.DEFAULT, new HitlBroker());
        var decision = interceptor.before(bash("git push origin main"), ctx);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class, decision);
    }

    @Test
    void projectRuleDenyRejects() throws Exception {
        Files.writeString(projectRules, "rules:\n  - tool: Bash\n    pattern: \"del *\"\n    action: deny\n");
        PermissionInterceptor interceptor = interceptor(PermissionMode.DEFAULT, new HitlBroker());
        var decision = interceptor.before(bash("del report.txt"), ctx);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, decision);
    }

    @Test
    void defaultModeAsksWhenNoRule() throws Exception {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.DEFAULT, broker);
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);
                broker.resolve("call-1", HitlDecision.ALLOW_ONCE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var decision = interceptor.before(bash("git push origin main"), ctx);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class, decision);
        t.join();
    }

    @Test
    void hitlAllowOnceDoesNotPersist() throws Exception {
        Files.writeString(projectRules, "rules: []\n");
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.DEFAULT, broker);
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);
                broker.resolve("call-1", HitlDecision.ALLOW_ONCE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(bash("git push origin main"), ctx));
        t.join();
        // 不产生持久规则
        RuleEngine reloaded = new RuleEngine(projectRules, userRules);
        assertTrue(reloaded.match("Bash", "git push origin main").isEmpty());
    }

    @Test
    void hitlAllowSessionAddsSessionRule() throws Exception {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.DEFAULT, broker);
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);
                broker.resolve("call-1", HitlDecision.ALLOW_SESSION);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(bash("git push origin main"), ctx));
        t.join();
        // 后续相同调用直接放行（无需 HITL）
        var decision2 = interceptor.before(bash("git push origin main"), ctx);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class, decision2);
    }

    @Test
    void sessionApprovalDoesNotAuthorizeOtherToolOrChangedContent() {
        HitlBroker broker = new HitlBroker(TimeUnit.MILLISECONDS.toNanos(200));
        AtomicInteger requests = new AtomicInteger();
        broker.setRequestListener(request -> broker.resolve(request.callId(),
                requests.incrementAndGet() == 1 ? HitlDecision.ALLOW_SESSION : HitlDecision.DENY));
        PermissionInterceptor interceptor = interceptor(PermissionMode.DEFAULT, broker);
        ToolCall original = writeFile("a.txt");
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(original, ctx));
        ToolCall changed = new ToolCall("call-2", "WriteFile",
                Map.of("path", "a.txt", "content", "different"), "", "openai");
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class,
                interceptor.before(changed, ctx));
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class,
                interceptor.before(bash("a.txt"), ctx));
        assertEquals(3, requests.get());
        String originalKey = PermissionRuleKey.of(original,
                new PathSandbox(new WorkspacePathResolver(tempDir.resolve("ws"))));
        String changedKey = PermissionRuleKey.of(changed,
                new PathSandbox(new WorkspacePathResolver(tempDir.resolve("ws"))));
        assertTrue(!originalKey.equals(changedKey));
    }

    @Test
    void hitlAllowForeverPersistsProjectRule() throws Exception {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.DEFAULT, broker);
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);
                broker.resolve("call-1", HitlDecision.ALLOW_FOREVER);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(bash("git push origin main"), ctx));
        t.join();
        assertTrue(Files.exists(projectRules));
        String content = Files.readString(projectRules);
        assertTrue(content.contains("sha256:"));
        // 新引擎重新加载后仍放行
        RuleEngine reloaded = new RuleEngine(projectRules, userRules);
        String key = PermissionRuleKey.of(bash("git push origin main"),
                new PathSandbox(new WorkspacePathResolver(tempDir.resolve("ws"))));
        assertEquals(java.util.Optional.of(RuleAction.ALLOW),
                reloaded.match("Bash", "git push origin main", key));
    }

    @Test
    void hitlDenyForeverPersistsDenyRule() throws Exception {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.DEFAULT, broker);
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);
                broker.resolve("call-1", HitlDecision.DENY_FOREVER);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var decision = interceptor.before(bash("del keep.txt"), ctx);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, decision);
        t.join();
        String content = Files.readString(projectRules);
        assertTrue(content.contains("sha256:"));
        assertTrue(content.contains("deny"));
    }

    @Test
    void lockedModeDeniesEvenReadTools() {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.LOCKED, broker);
        ToolCall read = new ToolCall("call-1", "ReadFile", Map.of("path", "a.txt"), "", "openai");
        var decision = interceptor.before(read, ctx);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, decision);
        assertEquals(0, broker.pendingCount());
    }

    @Test
    void readOnlyModeDeniesWrite() {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.READ_ONLY, broker);
        var decision = interceptor.before(writeFile("a.txt"), ctx);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, decision);
        assertEquals(0, broker.pendingCount());
    }

    @Test
    void trustAllAllowsEverything() throws Exception {
        Files.writeString(projectRules, "rules: []\n");
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.TRUST_ALL, broker);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(bash("echo hi"), ctx));
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(writeFile("a.txt"), ctx));
        assertEquals(0, broker.pendingCount());
    }

    @Test
    void lenientModeAllowsWriteAsksCommand() throws Exception {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.LENIENT, broker);
        // WRITE 直接放行
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(writeFile("a.txt"), ctx));
        // COMMAND 询问
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);
                broker.resolve("call-1", HitlDecision.DENY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var decision = interceptor.before(bash("git push origin main"), ctx);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, decision);
        t.join();
    }

    @Test
    void hitlAskNotifiesBrokerListenerForUiDialog() throws Exception {
        HitlBroker broker = new HitlBroker();
        java.util.concurrent.atomic.AtomicReference<dev.zencode.permission.hitl.HitlRequest> notified =
                new java.util.concurrent.atomic.AtomicReference<>();
        broker.setRequestListener(notified::set);
        PermissionInterceptor interceptor = interceptor(PermissionMode.DEFAULT, broker);

        Thread t = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);
                broker.resolve("call-1", HitlDecision.ALLOW_ONCE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(bash("git push origin main"), ctx));
        t.join();

        assertTrue(notified.get() != null, "HITL 触发时必须通知监听器渲染确认框");
        assertEquals("Bash", notified.get().toolName());
        assertEquals("git push origin main", notified.get().safeSummary());
    }

    @Test
    void safeCommandAutoAllowedWithoutHitl() {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.DEFAULT, broker);
        var decision = interceptor.before(bash("git status"), ctx);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class, decision);
        assertEquals(0, broker.pendingCount(), "只读安全命令不触发 HITL");
    }

    @Test
    void defaultModeAllowsReadWithoutHitl() {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.DEFAULT, broker);
        ToolCall read = new ToolCall("call-1", "ReadFile", Map.of("path", "a.txt"), "", "openai");
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(read, ctx));
        assertEquals(0, broker.pendingCount(), "DEFAULT 下 READ 自动放行");
    }

    @Test
    void lockedModeBlocksSafeCommandToo() {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.LOCKED, broker);
        var decision = interceptor.before(bash("git status"), ctx);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, decision);
        assertEquals(0, broker.pendingCount());
    }

    @Test
    void readOnlyModeBlocksSafeCommandToo() {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.READ_ONLY, broker);
        var decision = interceptor.before(bash("git status"), ctx);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class, decision);
        assertEquals(0, broker.pendingCount());
    }

    @Test
    void planModeAllowsPlanDirWrite() {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.PLAN, broker);
        ToolCall planWrite = new ToolCall("call-1", "WriteFile",
                Map.of("path", ".zencode/plans/plan.md", "content", "x"), "", "openai");
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(planWrite, ctx));
        assertEquals(0, broker.pendingCount(), "Plan 目录写入自动放行");
    }

    @Test
    void planModeRegularWriteStillAsks() throws Exception {
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.PLAN, broker);
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);
                broker.resolve("call-1", HitlDecision.ALLOW_ONCE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(writeFile("a.txt"), ctx));
        t.join();
    }
}
