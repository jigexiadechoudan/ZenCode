package dev.zencode.memory;

/**
 * 跨会话记忆系统配置。
 *
 * @param enabled                是否启用记忆系统，默认 true
 * @param extractIntervalRounds  每 N 轮对话触发一次自动记忆提取，默认 5
 * @param memoryMaxChars         单条记忆/指令文件注入内容的大小上限（字符数），默认 16384
 * @param includeMaxDepth        @include 嵌套深度上限，默认 3
 * @param sessionIdleHours       会话空闲超时阈值（小时），超过后恢复时插入时间提醒，默认 24
 * @param sessionArchive         是否开启会话存档，默认 true
 */
public record MemoryConfig(boolean enabled,
                           int extractIntervalRounds,
                           int memoryMaxChars,
                           int includeMaxDepth,
                           int sessionIdleHours,
                           boolean sessionArchive) {

    public MemoryConfig {
        if (extractIntervalRounds < 1) throw new IllegalArgumentException("extractIntervalRounds 必须 >= 1");
        if (memoryMaxChars < 1) throw new IllegalArgumentException("memoryMaxChars 必须 >= 1");
        if (includeMaxDepth < 1) throw new IllegalArgumentException("includeMaxDepth 必须 >= 1");
        if (sessionIdleHours < 1) throw new IllegalArgumentException("sessionIdleHours 必须 >= 1");
    }

    public static MemoryConfig defaults() {
        return new MemoryConfig(true, 5, 16384, 3, 24, true);
    }
}
