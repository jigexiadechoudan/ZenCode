package dev.zencode.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageAccumulatorTest {
    @Test
    void combinesUsageFieldsFromMultipleStreamingEvents() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UsageAccumulator accumulator = new UsageAccumulator();

        accumulator.add(mapper.readTree("{\"input_tokens\":12}"));
        accumulator.add(mapper.readTree("{\"output_tokens\":34}"));

        assertEquals(12, accumulator.toUsage().inputTokens());
        assertEquals(34, accumulator.toUsage().outputTokens());
        assertEquals(46, accumulator.toUsage().totalTokens());
    }

    @Test
    void acceptsOpenAiUsageFieldNames() throws Exception {
        UsageAccumulator accumulator = new UsageAccumulator();
        accumulator.add(new ObjectMapper().readTree(
                "{\"prompt_tokens\":7,\"completion_tokens\":9,\"total_tokens\":16}"));

        assertEquals(7, accumulator.toUsage().inputTokens());
        assertEquals(9, accumulator.toUsage().outputTokens());
        assertEquals(16, accumulator.toUsage().totalTokens());
    }
}
