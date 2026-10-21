package dev.zencode.chat;

import java.util.Objects;

public record ChatMessage(Role role, String text) {
    public ChatMessage {
        Objects.requireNonNull(role, "角色不能为空");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
    }
}
