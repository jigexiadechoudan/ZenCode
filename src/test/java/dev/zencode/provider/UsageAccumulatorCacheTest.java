package dev.zencode.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zencode.chat.Usage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** T4：Usage 缓存 token 的累积与跨协议解析。 */
class UsageAccumulatorCacheTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void accumulatesAnthropicCacheTokens() {
        UsageAccumulator acc = new UsageAccumulator();
        ObjectNode first = mapper.createObjectNode()
                .put("cache_creation_input_tokens", 100)
                .put("cache_read_input_tokens", 25)
                .put("input_tokens", 50);
        acc.add(first);
        acc.add(mapper.createObjectNode().put("cache_read_input_tokens", 25));

        Usage usage = acc.toUsage();
        assertEquals(100, usage.cacheCreationTokens());
        assertEquals(50, usage.cacheReadTokens());
        assertEquals(50, usage.inputTokens());
    }

    @Test
    void parsesOpenAiCachedTokensFromDetails() {
        UsageAccumulator acc = new UsageAccumulator();
        ObjectNode usage = mapper.createObjectNode()
                .put("prompt_tokens", 60)
                .put("completion_tokens", 10);
        usage.putObject("prompt_tokens_details").put("cached_tokens", 40);
        acc.add(usage);

        Usage result = acc.toUsage();
        assertEquals(40, result.cacheReadTokens());
        assertEquals(60, result.inputTokens());
        assertEquals(10, result.outputTokens());
    }

    @Test
    void missingCacheTokensAreNull() {
        UsageAccumulator acc = new UsageAccumulator();
        acc.add(mapper.createObjectNode().put("input_tokens", 10));
        assertEquals(null, acc.toUsage().cacheCreationTokens());
        assertEquals(null, acc.toUsage().cacheReadTokens());
    }

    @Test
    void ignoresNullOrMissingUsageNode() {
        UsageAccumulator acc = new UsageAccumulator();
        acc.add(null);
        acc.add(mapper.createObjectNode());
        assertEquals(null, acc.toUsage().cacheCreationTokens());
    }
}