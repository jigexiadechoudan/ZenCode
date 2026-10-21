package dev.zencode.permission;

import dev.zencode.permission.rule.RuleEngine;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.WorkspacePathResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 纯权限检查器的模式可变性测试：setMode 切换后决策实时变化。
 */
class PermissionCheckerTest {

    @Test
    void setModeChangesDecision() {
        PermissionChecker checker = checker(PermissionMode.DEFAULT);

        ToolCall command = new ToolCall("c1", "Bash", java.util.Map.of("command", "some-command"), "", "openai");

        // DEFAULT：COMMAND 未命中规则 → 询问
        assertEquals(PermissionDecision.REQUEST_HITL, checker.check(command).decision());

        // 切到 LOCKED：COMMAND 直接拒绝
        checker.setMode(PermissionMode.LOCKED);
        assertEquals(PermissionDecision.DENY_BLOCKED, checker.check(command).decision());

        // 切到 TRUST_ALL：COMMAND 放行
        checker.setMode(PermissionMode.TRUST_ALL);
        assertEquals(PermissionDecision.ALLOW, checker.check(command).decision());
    }

    @Test
    void readOnlyAffectsReadAndCommandDifferently() {
        PermissionChecker checker = checker(PermissionMode.READ_ONLY);
        ToolCall read = new ToolCall("c1", "ReadFile", java.util.Map.of("path", "ok.txt"), "", "openai");
        ToolCall command = new ToolCall("c2", "Bash", java.util.Map.of("command", "echo"), "", "openai");

        assertEquals(PermissionDecision.ALLOW, checker.check(read).decision());
        assertEquals(PermissionDecision.DENY_BLOCKED, checker.check(command).decision());

        checker.setMode(PermissionMode.LENIENT);
        assertEquals(PermissionDecision.ALLOW, checker.check(command).decision());
    }

    @Test
    void setModeNullFallsBackToDefault() {
        PermissionChecker checker = checker(null);
        assertEquals(PermissionMode.DEFAULT, checker.mode());
        checker.setMode(null);
        assertEquals(PermissionMode.DEFAULT, checker.mode());
    }

    @Test
    void modeReflectsConstructorValue() {
        PermissionChecker checker = checker(PermissionMode.LOCKED);
        assertEquals(PermissionMode.LOCKED, checker.mode());
    }

    @Test
    void shellControlSyntaxNeverUsesSafeCommandAutoAllow() {
        PermissionChecker checker = checker(PermissionMode.DEFAULT);
        String[] commands = {
                "git status & echo second",
                "git status\necho second",
                "git status^&echo second",
                "git status %ZENCODE_EXTRA%",
                "git status !ZENCODE_EXTRA!",
                "git status > output.txt",
                "git status $(echo second)",
                "git branch -D feature",
                "git diff --ext-diff",
                "go env -w GOFLAGS=-mod=mod"
        };
        for (String command : commands) {
            ToolCall call = new ToolCall("c1", "Bash", java.util.Map.of("command", command), "", "openai");
            assertEquals(PermissionDecision.REQUEST_HITL, checker.check(call).decision(), command);
        }
    }

    @Test
    void dangerousCommandIsBlockedBeforeSafeCommandCheck() {
        PermissionChecker checker = checker(PermissionMode.DEFAULT);
        ToolCall call = new ToolCall("c1", "Bash",
                java.util.Map.of("command", "echo ok & del /s work"), "", "openai");
        assertEquals(PermissionDecision.DENY_BLOCKED, checker.check(call).decision());
    }

    @Test
    void planModeRecognizesActualLowercaseAgentNameAndCanonicalPlanPath() {
        PermissionChecker checker = checker(PermissionMode.PLAN);
        ToolCall agent = new ToolCall("c1", "agent", java.util.Map.of("task", "只读探索"), "", "openai");
        assertEquals(PermissionDecision.ALLOW, checker.check(agent).decision());
        ToolCall escapedPlan = new ToolCall("c2", "WriteFile",
                java.util.Map.of("path", ".zencode/plans/../outside.txt", "content", "x"),
                "", "openai");
        assertEquals(PermissionDecision.REQUEST_HITL, checker.check(escapedPlan).decision());
    }

    private static PermissionChecker checker(PermissionMode mode) {
        Path ws = Path.of(".");
        ToolRegistry registry = ToolRegistry.createDefault(ws);
        RuleEngine ruleEngine = new RuleEngine(ws.resolve("no-project-rules.yaml"),
                ws.resolve("no-user-rules.yaml"));
        return new PermissionChecker(new DangerCommandDetector(),
                new PathSandbox(new WorkspacePathResolver(ws)), ruleEngine, mode, registry);
    }
}
