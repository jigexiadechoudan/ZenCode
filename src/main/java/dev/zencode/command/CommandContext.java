package dev.zencode.command;

/**
 * 命令执行上下文：携带应用能力入口与本次调用参数。
 *
 * @param ui          命令可调用的应用能力
 * @param rawCommand  触发的原始命令名（不含 /）
 * @param args        解析后的参数串（可为空）
 */
public record CommandContext(UiController ui, String rawCommand, String args) {
}