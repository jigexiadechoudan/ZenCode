package dev.zencode.prompt;

import java.util.Objects;

/**
 * 一个全局指令模块。
 * <p>
 * 内容为空或空白时视为空模块，组装时会被跳过、不产生多余分隔。
 * 优先级在模块间比较排序，数值越小越靠前。
 * </p>
 */
public record InstructionSection(String name, int priority, String content) {
    public InstructionSection {
        Objects.requireNonNull(name, "模块名不能为空");
        content = content == null ? "" : content;
    }

    /** 是否为需要忽略的空模块。 */
    public boolean isEmpty() {
        return content.isBlank();
    }
}