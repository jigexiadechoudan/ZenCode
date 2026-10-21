package dev.zencode.prompt;

import java.util.Objects;

/**
 * 七模块组装得到的稳定 System Prompt。
 * <p>
 * 文本按优先级升序拼接、跳过空模块、使用稳定分隔，可被 Provider 视为可缓存内容。
 * 文本一旦构建即不可变，属于长期稳定的全局指令，不随环境或轮次变化。
 * </p>
 */
public final class SystemPrompt {
    private final String text;

    public SystemPrompt(String text) {
        this.text = Objects.requireNonNull(text, "文本不能为空");
    }

    /** 组装结果文本。 */
    public String text() {
        return text;
    }

    /** 该内容属于稳定可缓存内容（恒为 true）。由请求组装阶段据此决定是否标记缓存断点。 */
    public boolean cacheable() {
        return true;
    }
}