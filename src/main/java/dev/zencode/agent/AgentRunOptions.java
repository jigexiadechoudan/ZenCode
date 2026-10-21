package dev.zencode.agent;

import java.time.Duration;
import java.util.Objects;

/**
 * 单次 Agent 运行的不可变选项。
 * <p>
 * 默认值为普通模式、最多 20 轮、模型流静默超时 120 秒、事件缓冲容量 64。
 * 构造时拒绝非正的轮数、超时和缓冲容量。
 * </p>
 */
public record AgentRunOptions(
        boolean planOnly,
        int maxRounds,
        Duration responseIdleTimeout,
        int eventBufferCapacity) {

    public AgentRunOptions {
        if (maxRounds <= 0) {
            throw new IllegalArgumentException("最大轮数必须为正数: " + maxRounds);
        }
        Objects.requireNonNull(responseIdleTimeout, "响应静默超时不能为空");
        if (responseIdleTimeout.isZero() || responseIdleTimeout.isNegative()) {
            throw new IllegalArgumentException("响应静默超时必须为正数: " + responseIdleTimeout);
        }
        if (eventBufferCapacity <= 0) {
            throw new IllegalArgumentException("事件缓冲容量必须为正数: " + eventBufferCapacity);
        }
    }

    /** 返回默认运行选项：普通模式、20 轮、120 秒静默超时、64 容量。 */
    public static AgentRunOptions defaults() {
        return new AgentRunOptions(false, 20, Duration.ofSeconds(120), 64);
    }
}
