package dev.zencode.command;

/**
 * 命令执行结果，由命令处理逻辑返回，上层据此解码为界面效果。
 */
public sealed interface CommandResult {

    /** 仅展示文本（状态栏/系统消息）。 */
    record ShowMessage(String text) implements CommandResult {}

    /** 要求把提示词作为用户消息提交给 Agent。 */
    record Submission(String promptText) implements CommandResult {}

    /** 触发一个显式 UI 动作。 */
    record UiAction(UiActionKind action) implements CommandResult {}

    /** UI 动作种类。 */
    enum UiActionKind {
        /** /permission 无参数时打开权限模式选择。 */
        OPEN_PERMISSION_PICKER,
        /** /clear 清空视图滚动历史。 */
        CLEAR_TRANSCRIPT,
        /** /status 刷新状态栏。 */
        REFRESH_STATUS,
        /** /plan 切换后状态栏模式提示。 */
        PLAN_SWITCHED,
        /** /permission 带参切换后状态栏提示。 */
        PERMISSION_SWITCHED
    }
}