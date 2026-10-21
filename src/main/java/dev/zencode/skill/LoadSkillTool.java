package dev.zencode.skill;

import dev.zencode.tool.BaseTool;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolPermission;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 系统工具 LoadSkill：按需加载完整 SOP 与专属工具并激活。
 * <p>
 * 目录型 Skill 激活时把自带工具（tool.json 声明）注册到工具注册中心。
 * 不受任何 Skill 白名单过滤，支持 Skill 嵌套触发。
 * </p>
 */
public final class LoadSkillTool extends BaseTool {

    private final SkillRegistry registry;
    private final ActiveSkills activeSkills;
    private final ToolRegistry toolRegistry;

    public LoadSkillTool(SkillRegistry registry, ActiveSkills activeSkills, ToolRegistry toolRegistry) {
        super(SystemToolNames.LOAD_SKILL,
                "加载一个 Skill 的完整指令与专属工具并激活到当前会话。"
                        + "参数 name 指定 Skill，arguments 可选（替换 $ARGUMENTS）。"
                        + "目录型 Skill 会同时注册其自带工具。"
                        + "本工具是系统工具，不受 Skill 白名单限制，可被 Skill 嵌套调用。",
                ToolCategory.COMMAND, ToolPermission.REQUIRES_CONFIRMATION,
                jsonSchema(Map.of(
                        "name", stringProp("要加载的 Skill 名字"),
                        "arguments", stringProp("传给 Skill 的参数（可选，替换 $ARGUMENTS）")
                ), List.of("name")));
        this.registry = registry;
        this.activeSkills = activeSkills;
        this.toolRegistry = toolRegistry;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> args, ToolExecutionContext ctx) {
        String name = requiredString(args, "name");
        String arguments = optionalString(args, "arguments", null);
        Optional<SkillDefinition> skill = registry.find(name);
        if (skill.isEmpty()) {
            return ToolResult.error(ctx.callId(), name(), "未知 Skill: " + name);
        }
        SkillDefinition definition = skill.get();
        if (definition.source().directory() && toolRegistry != null) {
            try {
                SkillToolCatalog.registerTools(definition.source().skillDir(), toolRegistry);
            } catch (SkillParseException e) {
                return ToolResult.error(ctx.callId(), name(), "Skill 工具注册失败: " + e.getMessage());
            }
        }
        activeSkills.activate(definition, arguments);
        return ToolResult.success(ctx.callId(), name(), "已加载并激活 Skill " + definition.name());
    }
}
