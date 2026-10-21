package dev.zencode.command;

/**
 * 命令执行模式，决定命令结果如何被上层处理。
 */
public enum CommandMode {
    /** 纯本地：仅执行逻辑并返回结果文本。 */
    LOCAL,
    /** 影响 UI 状态：需要 UI 侧动作（如打开浮层、清视图）。 */
    UI,
    /** 送入对话流：提交提示词触发 Agent 执行。 */
    SUBMIT
}