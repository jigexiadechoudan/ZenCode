package dev.zencode.skill;

import dev.zencode.agent.execution.ToolExecutionInterceptor;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolResult;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Skill 白名单执行拦截：激活 Skill 后，白名单外的工具调用被拒绝（RESTRICTED）。
 * 系统工具恒放行。白名单并集为空（无激活或均不限）时全放行。
 */
public final class SkillWhitelistInterceptor implements ToolExecutionInterceptor {

    private static final Set<String> SYSTEM_TOOLS_LOWER = SystemToolNames.ALL.stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private final ActiveSkills activeSkills;

    public SkillWhitelistInterceptor(ActiveSkills activeSkills) {
        this.activeSkills = activeSkills;
    }

    @Override
    public BeforeDecision before(ToolCall call, ToolExecutionContext context) {
        Set<String> union = activeSkills.allowedToolUnion();
        if (union.isEmpty()) {
            return new BeforeDecision.Allow();
        }
        String name = call.toolName().toLowerCase(Locale.ROOT);
        if (union.contains(name) || SYSTEM_TOOLS_LOWER.contains(name)) {
            return new BeforeDecision.Allow();
        }
        return new BeforeDecision.Reject(ToolResult.restricted(context.callId(), call.toolName(),
                "当前 Skill 白名单不允许使用工具: " + call.toolName()));
    }

    @Override
    public ToolResult after(ToolCall call, ToolResult result, ToolExecutionContext context) {
        return result;
    }
}
