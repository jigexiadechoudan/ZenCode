package dev.zencode.provider.sse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SseDecoderTest {
    @Test
    void decodesEventsAndMultilineData() throws Exception {
        String source = "event: message\ndata: 你好\ndata: 世界\n\nevent: done\ndata: {}\n\n";
        var events = new SseDecoder().decode(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, events.size());
        assertEquals("message", events.get(0).event());
        assertEquals("你好\n世界", events.get(0).data());
    }
}
