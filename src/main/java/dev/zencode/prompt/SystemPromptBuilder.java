package dev.zencode.prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 七模块 System Prompt 组装器（F1、N1）。
 * <p>
 * 将全局指令模块按优先级升序排列、跳过空模块，以稳定分隔拼接生成唯一文本。
 * 结果确定且可缓存：同一组模块总是产生完全相同的文本，不随环境、日期或状态变化。
 * </p>
 */
public final class SystemPromptBuilder {

    private final List<InstructionSection> sections;

    public SystemPromptBuilder(List<InstructionSection> sections) {
        this.sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /** 使用默认的七个全局模块构建。 */
    public static SystemPromptBuilder defaults() {
        return new SystemPromptBuilder(GlobalSections.sections());
    }

    /** 组装稳定的 System Prompt。 */
    public SystemPrompt build() {
        List<InstructionSection> ordered = new ArrayList<>(sections);
        ordered.sort(Comparator.comparingInt(InstructionSection::priority));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ordered.size(); i++) {
            InstructionSection section = ordered.get(i);
            if (section.isEmpty()) continue;
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("--- ").append(section.name()).append("\n");
            sb.append(section.content());
        }
        return new SystemPrompt(sb.toString());
    }

    /** 供组装阶段使用：模块列表（调试/测试可见）。 */
    public List<InstructionSection> sections() {
        return sections;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SystemPromptBuilder other)) return false;
        return sections.equals(other.sections);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sections);
    }
}