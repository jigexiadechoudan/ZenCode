package dev.zencode.hook;

/** 单条条件：字段 + 操作符 + 期望值。 */
public record Condition(String field, Operator operator, String value) {
}
