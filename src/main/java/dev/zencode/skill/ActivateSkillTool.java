package dev.zencode.skill;

import dev.zencode.tool.BaseTool;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 系统工具 ActivateSkill：把已加载 Skill 钉入会话激活列表（每轮 transient 注入其完整指令）。
 * 不受任何 Skill 白名单过滤，支持 Skill 之间嵌套触发。
 */
public final class ActivateSkillTool extends BaseTool {

    private final SkillRegistry registry;
    private final ActiveSkills activeSkills;

    public ActivateSkillTool(SkillRegistry registry, ActiveSkills activeSkills) {
        super(SystemToolNames.ACTIVATE_SKILL,
                "激活一个已加载的 Skill，把它的完整指令钉入当前会话环境上下文（每轮请求都携带）。"
                        + "参数 name 指定 Skill，arguments 可选，会替换指令中的 $ARGUMENTS。"
                        + "本工具是系统工具，不受 Skill 白名单限制，可被 Skill 嵌套调用。",
                ToolCategory.COMMAND, ToolPermission.REQUIRES_CONFIRMATION,
                jsonSchema(Map.of(
                        "name", stringProp("要激活的 Skill 名字"),
                        "arguments", stringProp("传给 Skill 的参数（可选，替换 $ARGUMENTS）")
                ), List.of("name")));
        this.registry = registry;
        this.activeSkills = activeSkills;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String name = requiredString(args, "name");
        String arguments = optionalString(args, "arguments", null);
        Optional<SkillDefinition> skill = registry.find(name);
        if (skill.isEmpty()) {
            return ToolResult.error(ctx.callId(), name(), "未知 Skill: " + name);
        }
        activeSkills.activate(skill.get(), arguments);
        return ToolResult.success(ctx.callId(), name(), "已激活 Skill " + skill.get().name());
    }
}
