package dev.zencode.team;

import dev.zencode.agent.execution.ToolExecutionInterceptor;
import dev.zencode.permission.DangerCommandDetector;
import dev.zencode.permission.PathSandbox;
import dev.zencode.permission.PermissionChecker;
import dev.zencode.permission.PermissionMode;
import dev.zencode.permission.rule.RuleEngine;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TeamApprovalFlowTest {
    @TempDir Path tempDir;

    @Test
    void replyMustMatchIdToolAndArgumentsAndCanBeUsedOnlyOnce() {
        TeamRuntime runtime = new TeamRuntime(new Team("alpha", "lead", 1), tempDir.resolve("team"));
        PermissionChecker checker = new PermissionChecker(new DangerCommandDetector(),
                new PathSandbox(new WorkspacePathResolver(tempDir)),
                new RuleEngine(null, null), PermissionMode.DEFAULT, ToolRegistry.createDefault(tempDir));
        TeamApprovalInterceptor interceptor = new TeamApprovalInterceptor(checker, runtime, "m-1");
        ToolCall call = new ToolCall("c1", "WriteFile",
                Map.of("path", "a.txt", "content", "hello"), "", "openai");
        ToolExecutionContext context = new ToolExecutionContext(tempDir, "c1");

        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class,
                interceptor.before(call, context));
        TeamMessage request = runtime.mailbox().readAll("lead").get(0);
        assertNotNull(request.protocol().get("approvalId"));
        assertNotNull(request.protocol().get("argumentKey"));

        Map<String, String> wrong = Map.of("request", "reply", "approvalId",
                request.protocol().get("approvalId"), "tool", "Bash", "argumentKey",
                request.protocol().get("argumentKey"), "allow", "true");
        runtime.mailbox().send("lead", "m-1", MessageType.APPROVAL, "wrong", "", wrong);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class,
                interceptor.before(call, context));

        Map<String, String> approved = Map.of("request", "reply", "approvalId",
                request.protocol().get("approvalId"), "tool", "WriteFile", "argumentKey",
                request.protocol().get("argumentKey"), "allow", "true");
        runtime.mailbox().send("lead", "m-1", MessageType.APPROVAL, "allow", "", approved);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.before(call, context));
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Allow.class,
                interceptor.revalidate(call, context));
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class,
                interceptor.before(call, context));
        assertEquals(2, runtime.mailbox().readAll("lead").size(), "第二次调用必须重新申请");

        TeamMessage second = runtime.mailbox().readAll("lead").get(1);
        Map<String, String> denied = Map.of("request", "reply", "approvalId",
                second.protocol().get("approvalId"), "tool", "WriteFile", "argumentKey",
                second.protocol().get("argumentKey"), "allow", "false");
        runtime.mailbox().send("lead", "m-1", MessageType.APPROVAL, "deny", "", denied);
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class,
                interceptor.before(call, context));
        assertInstanceOf(ToolExecutionInterceptor.BeforeDecision.Reject.class,
                interceptor.before(call, context));
        assertEquals(2, runtime.mailbox().readAll("lead").size(), "拒绝后不得重复申请");
    }
}
