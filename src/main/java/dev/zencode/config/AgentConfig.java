package dev.zencode.config;

/**
 * 子 Agent 系统配置。
 *
 * @param reviewEnabled            是否启用内置 review 角色（默认关闭）
 * @param backgroundTimeoutSeconds 后台任务自动超时秒数（0=不超时）
 */
public record AgentConfig(boolean reviewEnabled, long backgroundTimeoutSeconds) {

    public AgentConfig {
        if (backgroundTimeoutSeconds < 0) {
            throw new IllegalArgumentException("后台超时秒数不能为负: " + backgroundTimeoutSeconds);
        }
    }

    /** 默认配置：review 关闭，后台超时 300 秒。 */
    public static AgentConfig defaults() {
        return new AgentConfig(false, 300);
    }
}
