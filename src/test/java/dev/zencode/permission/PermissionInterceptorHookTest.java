package dev.zencode.permission;

import dev.zencode.agent.execution.ToolExecutionInterceptor;
import dev.zencode.hook.Hook;
import dev.zencode.hook.HookEngine;
import dev.zencode.hook.HookEvent;
import dev.zencode.hook.PromptAction;
import dev.zencode.permission.hitl.HitlBroker;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.rule.RuleEngine;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8：PermissionInterceptor 发起 HITL 前触发 permission_request（经注入缓存观察）。
 */
class PermissionInterceptorHookTest {

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

    private PermissionInterceptor interceptor(PermissionMode mode, HitlBroker broker, HookEngine engine) {
        RuleEngine ruleEngine = new RuleEngine(projectRules, userRules);
        PermissionChecker checker = new PermissionChecker(new DangerCommandDetector(),
                new PathSandbox(new dev.zencode.tool.WorkspacePathResolver(tempDir.resolve("ws"))),
                ruleEngine, mode, registry);
        return new PermissionInterceptor(checker, ruleEngine, broker, engine);
    }

    private ToolCall bash(String command) {
        return new ToolCall("call-1", "Bash", Map.of("command", command), "", "openai");
    }

    private static Hook promptHook(String id, HookEvent event, String text) {
        return new Hook(id, event, null, new PromptAction(text), false, false, false);
    }

    @Test
    void hitlAskTriggersPermissionRequest() throws Exception {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(promptHook("p", HookEvent.PERMISSION_REQUEST, "[PERMISSION_REQUEST]")));
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.DEFAULT, broker, engine);

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

        assertTrue(engine.injectedPrompts().contains("[PERMISSION_REQUEST]"),
                "HITL 询问时必须触发 permission_request");
    }

    @Test
    void autoAllowedCommandDoesNotTrigger() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(promptHook("p", HookEvent.PERMISSION_REQUEST, "[PERMISSION_REQUEST]")));
        HitlBroker broker = new HitlBroker();
        PermissionInterceptor interceptor = interceptor(PermissionMode.DEFAULT, broker, engine);

        interceptor.before(bash("git status"), ctx);

        assertTrue(engine.injectedPrompts().isEmpty(), "自动放行的安全命令不触发 permission_request");
    }
}
