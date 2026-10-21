package dev.zencode.config;

import java.net.URI;

public record ProviderConfig(String name, Protocol protocol, String model, URI baseUrl,
                             String apiKey, boolean thinking) {
}
