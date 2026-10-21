package dev.zencode.skill;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandRegistry;
import dev.zencode.command.CommandResult;
import dev.zencode.command.FakeUiController;
import dev.zencode.command.builtin.ClearCommand;
import dev.zencode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Skill 命令测试：/&lt;name&gt; 短命令、/skill list/info/reload。
 */
class SkillCommandTest {

    @TempDir
    Path tempDir;

    private SkillDefinition def(String name) {
        return new SkillDefinition(name, "说明-" + name, List.of("Bash"),
                SkillMode.INLINE, Optional.empty(), SkillContextPolicy.none(), false,
                "SOP $ARGUMENTS", new SkillSource(SkillSource.Level.PROJECT, Path.of(name + ".md"), false));
    }

    @Test
    void 短命令命中调用submitSkill() {
        SkillRegistry registry = new SkillRegistry();
        registry.replaceAll(Map.of("commit", def("commit")));
        SkillCommandHandler handler = new SkillCommandHandler(registry);

        FakeUiController ui = new FakeUiController();
        CommandResult result = handler.handle(new CommandContext(ui, "commit", "提交信息"));
        assertInstanceOf(CommandResult.ShowMessage.class, result);
        assertEquals("ok", ((CommandResult.ShowMessage) result).text());
        assertEquals(List.of("commit"), ui.skillSubmittedNames);
        assertEquals(List.of("提交信息"), ui.skillSubmittedArgs);
    }

    @Test
    void 短命令未知Skill提示() {
        SkillRegistry registry = new SkillRegistry();
        SkillCommandHandler handler = new SkillCommandHandler(registry);
        FakeUiController ui = new FakeUiController();
        CommandResult result = handler.handle(new CommandContext(ui, "ghost", ""));
        assertInstanceOf(CommandResult.ShowMessage.class, result);
        assertTrue(((CommandResult.ShowMessage) result).text().contains("未知 Skill"));
        assertTrue(ui.skillSubmittedNames.isEmpty());
    }

    @Test
    void skillList输出名称说明且标记激活() {
        SkillRegistry registry = new SkillRegistry();
        registry.replaceAll(Map.of("one", def("one"), "two", def("two")));
        ActiveSkills active = new ActiveSkills();
        active.activate(def("one"), null);
        SkillManager manager = new SkillManager(null, registry, active, new CommandRegistry(),
                ToolRegistry.createDefault(tempDir));
        SkillManageCommand command = new SkillManageCommand(manager, active);

        CommandResult result = command.handle(new CommandContext(new FakeUiController(), "skill", "list"));
        String text = ((CommandResult.ShowMessage) result).text();
        assertTrue(text.contains("one — 说明-one"));
        assertTrue(text.contains("two — 说明-two"));
        assertTrue(text.contains("* one"), "激活的 Skill 应加前缀");
    }

    @Test
    void skillInfo输出详情() {
        SkillRegistry registry = new SkillRegistry();
        registry.replaceAll(Map.of("commit", def("commit")));
        SkillManager manager = new SkillManager(null, registry, new ActiveSkills(),
                new CommandRegistry(), ToolRegistry.createDefault(tempDir));
        SkillManageCommand command = new SkillManageCommand(manager, new ActiveSkills());

        CommandResult result = command.handle(new CommandContext(new FakeUiController(), "skill", "info commit"));
        String text = ((CommandResult.ShowMessage) result).text();
        assertTrue(text.contains("Skill commit"));
        assertTrue(text.contains("INLINE"));
        assertTrue(text.contains("Bash"));
    }

    @Test
    void skillInfo未知提示() {
        SkillRegistry registry = new SkillRegistry();
        SkillManager manager = new SkillManager(null, registry, new ActiveSkills(),
                new CommandRegistry(), ToolRegistry.createDefault(tempDir));
        SkillManageCommand command = new SkillManageCommand(manager, new ActiveSkills());
        CommandResult result = command.handle(new CommandContext(new FakeUiController(), "skill", "info ghost"));
        assertTrue(((CommandResult.ShowMessage) result).text().contains("未知 Skill"));
    }

    @Test
    void skillReload重新扫描并注册新命令() throws IOException {
        Path work = tempDir.resolve("work");
        Path skills = work.resolve(".zencode").resolve("skills");
        Files.createDirectories(skills);
        Files.writeString(skills.resolve("one.md"), skillFile("one"));

        SkillLoader loader = new SkillLoader(work, tempDir, SkillConfig.defaults());
        ToolRegistry toolRegistry = ToolRegistry.createDefault(work);
        SkillRegistry registry = new SkillRegistry();
        registry.replaceAll(loader.loadAll(toolRegistry));
        ActiveSkills active = new ActiveSkills();
        CommandRegistry commandRegistry = new CommandRegistry();
        SkillManager manager = new SkillManager(loader, registry, active, commandRegistry, toolRegistry);
        manager.registerSkillCommands();
        assertTrue(commandRegistry.find("one").isPresent(), "reload 前应有 /one 命令");
        assertTrue(commandRegistry.find("skill").isPresent(), "应有 /skill 命令");

        Files.writeString(skills.resolve("two.md"), skillFile("two"));
        String result = manager.reload();
        assertTrue(result.contains("个 Skill"), "reload 应报告加载数量: " + result);
        assertTrue(commandRegistry.find("one").isPresent(), "旧命令应保留");
        assertTrue(commandRegistry.find("two").isPresent(), "新命令应注册");
    }

    private static String skillFile(String name) {
        return "---\nname: " + name + "\ndescription: 说明-" + name
                + "\nmode: inline\nallowedTools: [Bash]\n---\nbody " + name + "\n";
    }

    @Test
    void clearCommand清空激活Skill() {
        FakeUiController ui = new FakeUiController();
        CommandResult result = new ClearCommand().handle(new CommandContext(ui, "clear", ""));
        assertInstanceOf(CommandResult.UiAction.class, result);
        assertTrue(ui.clearActiveSkillsCalled, "clearActiveSkills 应被调用");
    }

    @Test
    void 内置review注册为短命令并解析到Skill() {
        SkillRegistry registry = new SkillRegistry();
        registry.replaceAll(Map.of("review", def("review")));
        CommandRegistry commandRegistry = new CommandRegistry();
        SkillManager manager = new SkillManager(null, registry, new ActiveSkills(),
                commandRegistry, ToolRegistry.createDefault(tempDir));
        manager.registerSkillCommands();
        assertTrue(commandRegistry.find("review").isPresent(), "/review 应解析到 review Skill 短命令");

        FakeUiController ui = new FakeUiController();
        CommandResult result = commandRegistry.find("review").get().handler()
                .handle(new CommandContext(ui, "review", "参数"));
        assertInstanceOf(CommandResult.ShowMessage.class, result);
        assertEquals(List.of("review"), ui.skillSubmittedNames);
    }
}
