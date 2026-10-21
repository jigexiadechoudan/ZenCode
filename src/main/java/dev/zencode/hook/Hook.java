package dev.zencode.hook;

import java.util.Objects;

/**
 * 一条 Hook 规则：事件 + 条件 + 动作 + 执行控制。
 * <p>condition 为空表示无条件触发；once/async/reject 默认 false。</p>
 */
public record Hook(String id, HookEvent event, ConditionGroup condition, Action action,
                   boolean once, boolean async, boolean reject) {

    public Hook {
        event = Objects.requireNonNull(event, "事件不能为空");
        action = Objects.requireNonNull(action, "动作不能为空");
    }
}
