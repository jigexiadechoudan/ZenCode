package dev.zencode.provider;

import com.fasterxml.jackson.databind.JsonNode;
import dev.zencode.chat.Usage;

/** 累积流式响应中分散返回的 token usage。 */
public final class UsageAccumulator {
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer thinkingTokens;
    private Integer cacheCreationTokens;
    private Integer cacheReadTokens;
    private Integer totalTokens;

    public void add(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) return;
        inputTokens = add(inputTokens, first(usage, "input_tokens", "prompt_tokens"));
        outputTokens = add(outputTokens, first(usage, "output_tokens", "completion_tokens"));
        thinkingTokens = add(thinkingTokens, first(usage, "thinking_tokens", "reasoning_tokens"));
        cacheCreationTokens = add(cacheCreationTokens, first(usage, "cache_creation_input_tokens"));
        cacheReadTokens = add(cacheReadTokens, first(usage, "cache_read_input_tokens"));
        totalTokens = add(totalTokens, first(usage, "total_tokens"));

        // OpenAI 内嵌的 prompt_tokens_details.cached_tokens（缓存命中）
        JsonNode details = usage.get("prompt_tokens_details");
        if (details != null && details.isObject()) {
            cacheReadTokens = add(cacheReadTokens, first(details, "cached_tokens"));
        }
    }

    public Usage toUsage() {
        Integer total = totalTokens == null ? add(inputTokens, outputTokens) : totalTokens;
        return new Usage(inputTokens, outputTokens, thinkingTokens,
                cacheCreationTokens, cacheReadTokens, total,
                null, null, null, null);
    }

    private static Integer first(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isNumber()) return value.intValue();
        }
        return null;
    }

    private static Integer add(Integer left, Integer right) {
        if (left == null) return right;
        if (right == null) return left;
        return left + right;
    }
}
