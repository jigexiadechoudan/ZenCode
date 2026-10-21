package dev.zencode.config;

/**
 * 上下文管理配置：窗口上限、触发比例、存盘阈值与熔断次数。
 * <p>
 * 缺省使用 {@link #defaults()}，保证不配置时行为与既有版本一致。
 * </p>
 */
public record ContextConfig(
        long windowTokens,
        double compactRatio,
        int singleResultThresholdBytes,
        int roundTotalThresholdBytes,
        int maxConsecutiveSummaryFailures) {

    public ContextConfig {
        if (windowTokens <= 0) {
            throw new IllegalArgumentException("窗口 token 上限必须为正数: " + windowTokens);
        }
        if (compactRatio <= 0 || compactRatio > 1) {
            throw new IllegalArgumentException("压缩触发比例必须在 (0,1] 区间: " + compactRatio);
        }
        if (singleResultThresholdBytes <= 0) {
            throw new IllegalArgumentException("单条结果阈值必须为正数: " + singleResultThresholdBytes);
        }
        if (roundTotalThresholdBytes <= 0) {
            throw new IllegalArgumentException("单轮合计阈值必须为正数: " + roundTotalThresholdBytes);
        }
        if (maxConsecutiveSummaryFailures <= 0) {
            throw new IllegalArgumentException("熔断次数必须为正数: " + maxConsecutiveSummaryFailures);
        }
    }

    /** 默认上下文配置：窗口 200000 token、80% 触发、单条 4KiB、合计 32KiB、熔断 3 次。 */
    public static ContextConfig defaults() {
        return new ContextConfig(200_000L, 0.8, 4096, 32 * 1024, 3);
    }
}
