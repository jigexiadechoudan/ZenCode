package dev.zencode.provider.sse;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class SseDecoder {
    public List<SseEvent> decode(InputStream input) throws IOException {
        List<SseEvent> events = new ArrayList<>();
        decode(input, events::add);
        return events;
    }

    public void decode(InputStream input, Consumer<SseEvent> consumer) throws IOException {
        String event = null;
        StringBuilder data = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (event != null || data.length() > 0) {
                    consumer.accept(new SseEvent(event, removeTrailingNewline(data)));
                }
                event = null;
                data.setLength(0);
            } else if (!line.startsWith(":")) {
                int separator = line.indexOf(':');
                String field = separator < 0 ? line : line.substring(0, separator);
                String value = separator < 0 ? "" : line.substring(separator + 1).stripLeading();
                if (field.equals("event")) event = value;
                if (field.equals("data")) data.append(value).append('\n');
            }
        }
        }
        if (event != null || data.length() > 0) consumer.accept(new SseEvent(event, removeTrailingNewline(data)));
    }

    private static String removeTrailingNewline(StringBuilder data) {
        if (!data.isEmpty()) data.setLength(data.length() - 1);
        return data.toString();
    }

}
