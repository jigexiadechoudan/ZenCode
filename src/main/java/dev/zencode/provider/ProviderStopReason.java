package dev.zencode.provider;

/**
 * 标准化的模型结束原因。
 * <p>
 * Anthropic 与 OpenAI 的原始结束原因在各适配器中归一化到本枚举。
 * {@code MAX_TOKENS} 与无法恢复的 {@code OTHER} 在本阶段作为安全错误终止，不自动续写或重试。
 * </p>
 */
public enum ProviderStopReason {
    /** 模型正常结束。 */
    END_TURN,
    /** 模型请求工具调用。 */
    TOOL_USE,
    /** 达到 token 上限。 */
    MAX_TOKENS,
    /** 其他无法恢复的结束原因。 */
    OTHER
}
