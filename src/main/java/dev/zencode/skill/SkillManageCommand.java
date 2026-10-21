package dev.zencode.skill;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;

import java.util.List;
import java.util.Set;

/**
 * /skill 管理命令：list / info / reload。
 */
public final class SkillManageCommand implements CommandHandler {

    private final SkillManager manager;
    private final ActiveSkills activeSkills;

    public SkillManageCommand(SkillManager manager, ActiveSkills activeSkills) {
        this.manager = manager;
        this.activeSkills = activeSkills;
    }

    @Override
    public CommandResult handle(CommandContext ctx) {
        String args = ctx.args() == null ? "" : ctx.args().trim();
        String[] parts = args.split("\\s+");
        String sub = parts.length == 0 ? "" : parts[0];
        return switch (sub) {
            case "list" -> new CommandResult.ShowMessage(listText());
            case "info" -> new CommandResult.ShowMessage(infoText(parts.length > 1 ? parts[1] : ""));
            case "reload" -> new CommandResult.ShowMessage(manager.reload());
            default -> new CommandResult.ShowMessage(
                    "用法: /skill list | info <name> | reload");
        };
    }

    private String listText() {
        Set<String> activeNames = Set.copyOf(activeSkills.names());
        StringBuilder sb = new StringBuilder("技能列表：");
        List<SkillDefinition> skills = manager.registry().all();
        if (skills.isEmpty()) {
            return "技能列表：（空）";
        }
        for (SkillDefinition skill : skills) {
            sb.append("\n");
            if (activeNames.contains(skill.name())) {
                sb.append("* ");
            }
            sb.append(skill.name()).append(" — ").append(skill.description());
        }
        return sb.toString();
    }

    private String infoText(String name) {
        if (name.isBlank()) {
            return "用法: /skill info <name>";
        }
        var found = manager.registry().find(name);
        if (found.isEmpty()) {
            return "未知 Skill: " + name;
        }
        SkillDefinition skill = found.get();
        StringBuilder sb = new StringBuilder("Skill ").append(skill.name()).append("：");
        sb.append("\n说明: ").append(skill.description());
        sb.append("\n来源: ").append(skill.source().levelName());
        sb.append("\n模式: ").append(skill.mode());
        sb.append("\n上下文策略: ").append(skill.context().type()).append(" (")
                .append(skill.context().recentCount()).append(")");
        sb.append("\n允许工具: ").append(String.join(", ", skill.allowedTools()));
        sb.append("\n模型: ").append(skill.model().map(Object::toString).orElse("默认"));
        sb.append("\n激活态: ").append(activeSkills.names().contains(skill.name()) ? "已激活" : "未激活");
        return sb.toString();
    }
}
