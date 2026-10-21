package dev.zencode.provider;

import dev.zencode.config.ProviderConfig;
import dev.zencode.provider.anthropic.AnthropicProvider;
import dev.zencode.provider.openai.OpenAiProvider;

import java.net.http.HttpClient;
import java.time.Duration;

public final class ProviderFactory {
    private ProviderFactory() {}

    public static ChatProvider create(ProviderConfig config) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        return switch (config.protocol()) {
            case ANTHROPIC -> new AnthropicProvider(config, client);
            case OPENAI -> new OpenAiProvider(config, client);
        };
    }
}
