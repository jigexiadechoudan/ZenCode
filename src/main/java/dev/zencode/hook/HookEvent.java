package dev.zencode.hook;

import java.util.Locale;

/**
 * Hook 生命周期事件常量（15 个）：会话级、轮次级、消息级、工具级、系统级与场景化。
 */
public enum HookEvent {
    SESSION_START, SESSION_END,
    TURN_START, TURN_END,
    PRE_TOOL_USE, POST_TOOL_USE,
    PRE_SEND, POST_RECEIVE,
    STARTUP, SHUTDOWN, ERROR, COMPACT,
    PERMISSION_REQUEST, FILE_CHANGE, COMMAND_EXECUTE;

    /** 按小写下划线名称解析；未知名称抛 {@link IllegalArgumentException}。 */
    public static HookEvent fromString(String name) {
        if (name == null) throw new IllegalArgumentException("Hook 事件名不能为空");
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (HookEvent event : values()) {
            if (event.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return event;
            }
        }
        throw new IllegalArgumentException("未知 Hook 事件: " + name);
    }
}
