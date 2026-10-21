package dev.zencode.command;

/**
 * 命令解析结果：规范化命令名 + 参数串。
 *
 * @param name 规范化命令名（小写、不含前缀输入中的多余空白）
 * @param args 参数串（去首尾空白；无参数时空字符串）
 */
public record ParsedCommand(String name, String args) {
}