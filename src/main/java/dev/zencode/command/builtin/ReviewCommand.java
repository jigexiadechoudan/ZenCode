package dev.zencode.command.builtin;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;

/**
 * /review：把预设代码审查提示词作为一条消息提交给 AI，审查当前对话上下文。
 */
public final class ReviewCommand implements CommandHandler {

    /** 预设审查提示词模板，引导 AI 审查当前对话中涉及的项目代码。 */
    static final String REVIEW_PROMPT = """
            请对当前对话上下文中涉及的项目代码进行一次代码审查，重点检查：
            1. 代码质量：命名、结构、重复、可维护性
            2. 安全性：注入、路径穿越、危险命令、敏感信息泄露
            3. 一致性：与项目现有约定（语言、风格、库选择）是否一致
            4. 正确性：边界情况、错误处理、并发安全

            输出格式：先给出总体结论，再按「问题」列出具体的文件/位置、问题描述与修改建议。
            """;

    @Override
    public CommandResult handle(CommandContext ctx) {
        String prompt = REVIEW_PROMPT.trim();
        boolean submitted = ctx.ui().submit(prompt);
        if (submitted) {
            return new CommandResult.Submission(prompt);
        }
        return new CommandResult.ShowMessage("提交失败（可能忙碌中）");
    }
}