package dev.zencode.chat;

import java.time.Duration;

public record Usage(Integer inputTokens, Integer outputTokens, Integer thinkingTokens,
                    Integer cacheCreationTokens, Integer cacheReadTokens,
                    Integer totalTokens, Duration duration, Duration timeToFirstDelta,
                    String finishReason, String requestId) {
    public static Usage unknown() {
        return new Usage(null, null, null, null, null, null, null, null, null, null);
    }
}
