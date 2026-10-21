package dev.zencode.prompt;

/**
 * Plan Mode 动态提醒策略。
 * <p>
 * Plan Mode 指令以轮次级 {@code system-reminder} 注入，而非拼接进基础 System Prompt。
 * 第 1、6、11、16 轮及之后每隔 5 轮注入完整版，其余轮次注入精简版（F5）。
 * 普通模式不调用本策略，由请求组装阶段控制。
 * </p>
 */
public final class PlanReminderPolicy {

    public enum Variant { NONE, CONCISE, FULL }

private static final String FULL =
            "你当前处于计划模式（plan-only）。\n"
            + "你只能使用只读工具（读取、搜索等）研究现状，不得修改任何文件或执行任何命令。\n"
            + "在给出最终答复时，输出一份可供用户审批的实施计划，说明将修改哪些文件、"
            + "执行哪些命令及其原因；不要真正执行计划中的任何操作。";

    private static final String CONCISE =
            "仍是计划模式：只读调研，禁止修改文件或执行命令，最终输出实施计划。";

    private PlanReminderPolicy() {}

    /**
     * 根据轮次返回 Plan Mode reminder 变体（round 从 1 开始）。
     * 返回常量字符串文本，便于组装阶段直接取用。
     */
    public static Variant variantForRound(int round) {
        if (round < 1) return Variant.NONE;
        if (round == 1 || round == 6 || round == 11 || round == 16) return Variant.FULL;
        if (round > 16 && (round - 16) % 5 == 0) return Variant.FULL;
        return Variant.CONCISE;
    }

    /** 完整版 Plan Mode reminder 纯文本（由组装层以 ReminderText.wrap 包裹）。 */
    public static String fullText() {
        return FULL;
    }

    /** 精简版 Plan Mode reminder 纯文本。 */
    public static String conciseText() {
        return CONCISE;
    }
}