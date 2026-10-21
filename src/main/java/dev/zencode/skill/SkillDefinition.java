package dev.zencode.skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 解析后的 Skill 定义：frontmatter 元信息 + Markdown body SOP 指令。
 *
 * @param name         唯一名（小写）
 * @param description  一句话说明（启动摘要注入用）
 * @param allowedTools 可用工具白名单；空列表=不限制
 * @param mode         执行模式
 * @param model        所用模型（声明不强制）
 * @param context      fork 历史携带策略
 * @param hidden       是否隐藏（不出现在 help/摘要）
 * @param body         SOP 指令（可含 $ARGUMENTS）
 * @param source       来源层级与位置
 */
public record SkillDefinition(String name, String description, List<String> allowedTools,
                              SkillMode mode, Optional<String> model, SkillContextPolicy context,
                              boolean hidden, String body, SkillSource source) {

    public SkillDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Skill 名不能为空");
        name = name.toLowerCase(java.util.Locale.ROOT);
        if (description == null || description.isBlank()) throw new IllegalArgumentException("Skill 描述不能为空");
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        if (mode == null) throw new IllegalArgumentException("Skill 模式不能为空");
        model = model == null ? Optional.empty() : model;
        context = context == null ? SkillContextPolicy.none() : context;
        if (body == null) throw new IllegalArgumentException("Skill 正文不能为空");
        if (source == null) throw new IllegalArgumentException("Skill 来源不能为空");
    }

    /** 把 body 中所有 $ARGUMENTS 占位符替换为调用参数。 */
    public String bodyWith(String args) {
        String replacement = args == null ? "" : args;
        return body.replace("$ARGUMENTS", replacement);
    }

    /** 注册为短命令时的命令名（带 / 前缀）。 */
    public String command() {
        return "/" + name;
    }

    /** 携带的工具白名单是否为空（空=不限制）。 */
    public boolean hasToolLimit() {
        return !allowedTools.isEmpty();
    }
}
