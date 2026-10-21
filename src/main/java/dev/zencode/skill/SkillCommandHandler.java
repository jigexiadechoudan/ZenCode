package dev.zencode.skill;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;

/**
 * /<skill> 短命令：把 Skill 作为 SUBMIT 命令提交。
 */
public final class SkillCommandHandler implements CommandHandler {

    private final SkillRegistry registry;

    public SkillCommandHandler(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public CommandResult handle(CommandContext ctx) {
        String name = ctx.rawCommand();
        if (!registry.contains(name)) {
            return new CommandResult.ShowMessage("未知 Skill: " + name);
        }
        String text = ctx.ui().submitSkill(name, ctx.args());
        return new CommandResult.ShowMessage(text);
    }
}
