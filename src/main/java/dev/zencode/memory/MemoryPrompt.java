package dev.zencode.memory;

import java.util.List;

/**
 * 记忆提取 Prompt 构造器。
 * <p>
 * 生成用于 LLM 提取记忆的提示文本，要求模型输出四节分类更新内容。
 * </p>
 */
public final class MemoryPrompt {

    private MemoryPrompt() {}

    /**
     * 构造提取 prompt。
     *
     * @param currentMemories 现有记忆内容（空字符串表示无现有记忆）
     * @param recentText     最近对话文本（多轮拼接）
     * @return 提取 prompt 文本
     */
    public static String build(String currentMemories, String recentText) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个记忆提取系统。请从最近对话中提取值得记住的信息，");
        sb.append("按以下分类输出完整更新文本。\n\n");

        sb.append("## 现有记忆\n");
        if (currentMemories.isEmpty()) {
            sb.append("（无）\n");
        } else {
            sb.append(currentMemories).append("\n");
        }

        sb.append("\n## 最近对话\n");
        sb.append(recentText).append("\n");

        sb.append("\n## 输出格式\n");
        sb.append("请输出以下四个分类的完整更新文本（包含现有记忆与新增内容的合并结果），");
        sb.append("用 Markdown 标题分隔：\n\n");

        sb.append("### 用户偏好\n");
        sb.append("（用户的编码习惯、工具选择、界面偏好等）\n\n");

        sb.append("### 纠正反馈\n");
        sb.append("（用户指出的错误、不喜欢的做法、修正过的方向）\n\n");

        sb.append("### 项目知识\n");
        sb.append("（技术栈、架构决策、业务规则、代码约定）\n\n");

        sb.append("### 参考资料\n");
        sb.append("（外部链接、文档、依赖库信息）\n\n");

        sb.append("### 输出规则\n");
        sb.append("- 每个分类输出完整的合并后文本，不要只输出增量。\n");
        sb.append("- 没有新信息时保留原有内容，不要删除。\n");
        sb.append("- 不要包含对话中的敏感信息（API Key、密码等）。\n");
        sb.append("- 不要调用任何工具。\n");
        sb.append("- 只输出 Markdown 文本，不要添加额外说明。\n");

        return sb.toString();
    }
}
