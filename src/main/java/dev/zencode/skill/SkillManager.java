package dev.zencode.skill;

import dev.zencode.command.CommandMode;
import dev.zencode.command.CommandRegistry;
import dev.zencode.command.CommandSpec;
import dev.zencode.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Skill 命令协调：为每个 Skill 注册 /<name> 短命令，并提供 reload（移除→重扫→重注册）。
 */
public final class SkillManager {

    private final SkillLoader loader;
    private final SkillRegistry registry;
    private final ActiveSkills activeSkills;
    private final CommandRegistry commandRegistry;
    private final ToolRegistry toolRegistry;
    private final List<String> registeredCommands = new ArrayList<>();

    public SkillManager(SkillLoader loader, SkillRegistry registry, ActiveSkills activeSkills,
                        CommandRegistry commandRegistry, ToolRegistry toolRegistry) {
        this.loader = loader;
        this.registry = registry;
        this.activeSkills = activeSkills;
        this.commandRegistry = commandRegistry;
        this.toolRegistry = toolRegistry;
    }

    public SkillRegistry registry() {
        return registry;
    }

    /** 为每个 Skill 注册 /<name> 命令，并注册 /skill 管理命令。 */
    public void registerSkillCommands() {
        for (SkillDefinition skill : registry.all()) {
            String command = "/" + skill.name();
            try {
                commandRegistry.register(new CommandSpec(command, List.of(), skill.description(),
                        command + " <参数>", "参数", CommandMode.SUBMIT, skill.hidden(),
                        new SkillCommandHandler(registry)));
                registeredCommands.add(command);
            } catch (IllegalArgumentException e) {
                // 与既有命令（如内置命令）重名时跳过该 Skill 的短命令
                System.err.println("Skill 命令注册失败（可能重名）: " + command + " - " + e.getMessage());
            }
        }
        commandRegistry.register(new CommandSpec("/skill", List.of(), "技能管理",
                "/skill list|info <name>|reload", "list / info <name> / reload",
                CommandMode.LOCAL, false, new SkillManageCommand(this, activeSkills)));
        registeredCommands.add("/skill");
    }

    /** 重新加载全部 Skill：移除本管理器注册的命令 → 重扫 → 替换 → 重注册。 */
    public String reload() {
        for (String command : registeredCommands) {
            commandRegistry.remove(command);
        }
        registeredCommands.clear();
        final Map<String, SkillDefinition> loaded;
        try {
            loaded = loader.loadAll(toolRegistry);
        } catch (RuntimeException e) {
            return "Skill 重载失败: " + e.getMessage();
        }
        registry.replaceAll(loaded);
        registerSkillCommands();
        return "已重新加载 " + loaded.size() + " 个 Skill";
    }
}
