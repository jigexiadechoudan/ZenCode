package dev.zencode.config;

/**
 * 团队系统配置。
 *
 * @param coordinatorEnabled Coordinator Mode 第一锁（配置开关）；缺省 false
 */
public record TeamConfig(boolean coordinatorEnabled) {

    public static TeamConfig defaults() {
        return new TeamConfig(false);
    }
}
