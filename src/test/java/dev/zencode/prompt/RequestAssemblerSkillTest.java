package dev.zencode.prompt;

import dev.zencode.chat.Conversation;
import dev.zencode.config.Protocol;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.skill.ActiveSkills;
import dev.zencode.skill.SkillContextPolicy;
import dev.zencode.skill.SkillDefinition;
import dev.zencode.skill.SkillMode;
import dev.zencode.skill.SkillRegistry;
import dev.zencode.skill.SkillSource;
import dev.zencode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RequestAssembler Skill 通道测试：摘要 seed 注入、transient 激活指令注入、Schema 白名单过滤。
 */
class RequestAssemblerSkillTest {

    @TempDir
    Path tempDir;

    private SkillDefinition skill(String name, List<String> allowedTools, boolean hidden) {
        return new SkillDefinition(name, "说明-" + name, allowedTools,
                SkillMode.INLINE, Optional.empty(), SkillContextPolicy.none(), hidden,
                "SOP $ARGUMENTS", new SkillSource(SkillSource.Level.PROJECT, Path.of(name + ".md"), false));
    }

    private RequestAssembler assembler(SkillRegistry registry, ActiveSkills active,
                                       ToolRegistry toolRegistry) {
        return new RequestAssembler(Path.of("."), "test",
                SystemPromptBuilder.defaults().build(), List.of(), null, null,
                registry, toolRegistry, Protocol.ANTHROPIC, active);
    }

    @Test
    void 首轮注入摘要seed轮次() {
        SkillRegistry registry = new SkillRegistry();
        registry.replaceAll(Map.of("commit", skill("commit", List.of(), false),
                "review", skill("review", List.of(), false),
                "ghost", skill("ghost", List.of(), true)));
        RequestAssembler assembler = assembler(registry, new ActiveSkills(), ToolRegistry.createDefault(tempDir));

        Conversation conversation = new Conversation();
        ProviderRequest first = assembler.assemble(conversation, 1, false, "任务");

        assertTrue(first.history().stream().anyMatch(t ->
                t.userMessage() != null && t.userMessage().text().contains("可用技能列表")),
                "首轮应注入技能摘要");
        String summary = first.history().stream()
                .map(t -> t.userMessage() == null ? "" : t.userMessage().text())
                .filter(text -> text.contains("可用技能列表"))
                .findFirst().orElse("");
        assertTrue(summary.contains("commit: 说明-commit"), "摘要应含 commit 名与说明");
        assertTrue(summary.contains("review: 说明-review"), "摘要应含 review 名与说明");
        assertFalse(summary.contains("ghost"), "hidden Skill 不应进入摘要");
        assertTrue(assembler.isSeeded(conversation));
    }

    @Test
    void 激活后每轮transient注入skill指令且在最前() {
        SkillRegistry registry = new SkillRegistry();
        registry.replaceAll(Map.of("commit", skill("commit", List.of(), false)));
        ActiveSkills active = new ActiveSkills();
        RequestAssembler assembler = assembler(registry, active, ToolRegistry.createDefault(tempDir));

        Conversation conversation = new Conversation();
        active.activate(registry.find("commit").get(), "添加登录");

        ProviderRequest request = assembler.assemble(conversation, 1, true, "任务");
        assertEquals(2, request.transientReminders().size(), "激活指令 + plan reminder");
        assertTrue(request.transientReminders().get(0).text().contains("已激活 Skill: commit"),
                "skill 指令应位于 transient 最前");
        assertTrue(request.transientReminders().get(0).text().contains("添加登录"),
                "注入的 skill 指令应已替换 $ARGUMENTS");
        assertTrue(request.transientReminders().get(1).text().contains("计划模式"),
                "plan reminder 应在 skill 之后");
    }

    @Test
    void 白名单非空时schema只含白名单内加系统工具() {
        SkillRegistry registry = new SkillRegistry();
        registry.replaceAll(Map.of("limited", skill("limited", List.of("Bash", "ReadFile"), false)));
        ActiveSkills active = new ActiveSkills();
        active.activate(registry.find("limited").get(), null);
        ToolRegistry toolRegistry = ToolRegistry.createDefault(tempDir);
        toolRegistry.register(new dev.zencode.skill.ActivateSkillTool(registry, active));
        toolRegistry.register(new dev.zencode.skill.LoadSkillTool(registry, active, toolRegistry));
        RequestAssembler assembler = assembler(registry, active, toolRegistry);

        ProviderRequest request = assembler.assemble(new Conversation(), 1, false, "任务");
        List<String> names = request.toolSchemas().stream()
                .map(schema -> (String) schema.get("name"))
                .toList();
        assertEquals(List.of("ReadFile", "Bash", "ActivateSkill", "LoadSkill"), names,
                "Schema 应只含白名单内工具 + 系统工具");
    }

    @Test
    void 无激活时schema全量() {
        SkillRegistry registry = new SkillRegistry();
        registry.replaceAll(Map.of("limited", skill("limited", List.of("Bash"), false)));
        ActiveSkills active = new ActiveSkills();
        RequestAssembler assembler = assembler(registry, active, ToolRegistry.createDefault(tempDir));

        ProviderRequest request = assembler.assemble(new Conversation(), 1, false, "任务");
        assertEquals(6, request.toolSchemas().size(), "无激活时应返回全部工具 schema");
    }
}
