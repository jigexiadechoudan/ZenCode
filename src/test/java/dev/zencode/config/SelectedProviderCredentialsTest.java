package dev.zencode.config;

import dev.zencode.cli.CliOptions;
import dev.zencode.web.ModelConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Offline configuration-only regressions; callbacks never construct or call a model client. */
class SelectedProviderCredentialsTest {
    @TempDir Path directory;

    @Test
    void inactiveMissingEnvironmentReferenceDoesNotBlockValidDefault() {
        String inactiveReference = missingEnvironmentReference();
        ConfigLoader.LoadedConfig loaded = new ConfigLoader().loadYaml(
                yaml("one", "fake-one-key", inactiveReference), options(null));

        assertEquals("one", loaded.selectedProvider().name());
        assertEquals("fake-one-key", loaded.selectedProvider().apiKey());
        assertEquals(inactiveReference, loaded.appConfig().provider("two").apiKey());
    }

    @Test
    void explicitSelectionBypassesMissingDefaultCredential() {
        String inactiveReference = missingEnvironmentReference();
        ConfigLoader.LoadedConfig loaded = new ConfigLoader().loadYaml(
                yaml("one", inactiveReference, "fake-two-key"), options("two"));

        assertEquals("two", loaded.selectedProvider().name());
        assertEquals("fake-two-key", loaded.selectedProvider().apiKey());
        assertEquals(inactiveReference, loaded.appConfig().provider("one").apiKey());
    }

    @Test
    void selectedProviderStillRequiresItsEnvironmentVariable() {
        String missingReference = missingEnvironmentReference();
        ConfigLoader loader = new ConfigLoader();

        assertThrows(ConfigException.class, () -> loader.loadYaml(
                yaml("one", missingReference, "fake-two-key"), options(null)));
        assertThrows(ConfigException.class, () -> loader.loadYaml(
                yaml("one", "fake-one-key", missingReference), options("two")));
    }

    @Test
    void settingsCanRepairAndApplyOneProviderWhileAnotherReferenceIsMissing() throws Exception {
        Path file = directory.resolve("config.local.yaml");
        String inactiveReference = missingEnvironmentReference();
        Files.writeString(file, yaml("two", missingEnvironmentReference(), inactiveReference));
        CliOptions options = CliOptions.parse(new String[]{"--config", file.toString()});
        ModelConfigStore store = new ModelConfigStore(options, null);
        AtomicReference<ProviderConfig> applied = new AtomicReference<>();

        ModelConfigStore.Settings result = store.save(new ModelConfigStore.Edit(
                store.read().revision(), "one", "one", "openai", "one-model",
                "https://one.example/v1", "fake-repaired-key", false, true), (config, persist) -> {
            persist.run();
            applied.set(config.selectedProvider());
        });

        assertFalse(result.setupRequired());
        assertFalse(result.applyRequired());
        assertEquals("", result.applicationError());
        assertEquals("one", result.activeProvider());
        assertNotNull(applied.get());
        assertEquals("fake-repaired-key", applied.get().apiKey());
        String saved = Files.readString(file);
        assertFalse(saved.contains("api_key"));
        assertFalse(saved.contains("fake-repaired-key"));
        assertFalse(saved.contains(inactiveReference));
        assertThrows(ConfigException.class, () -> new ConfigLoader().load(options));
        assertFalse(new ModelConfigStore(options, null).read().providers().stream()
                .anyMatch(ModelConfigStore.ProviderSummary::keyConfigured));
        assertThrows(ConfigException.class, () -> new ConfigLoader().load(
                CliOptions.parse(new String[]{"--config", file.toString(), "--provider", "two"})));
    }

    private static CliOptions options(String provider) {
        return CliOptions.parse(provider == null ? new String[]{} : new String[]{"--provider", provider});
    }

    private static String missingEnvironmentReference() {
        String name;
        do {
            name = "ZENCODE_TEST_UNSET_" + UUID.randomUUID().toString().replace("-", "");
        } while (System.getenv(name) != null);
        return "${" + name + "}";
    }

    private static String yaml(String defaultName, String firstKey, String secondKey) {
        return """
                default: %s
                providers:
                  - name: one
                    protocol: openai
                    model: one-model
                    base_url: https://one.example/v1
                    api_key: '%s'
                  - name: two
                    protocol: anthropic
                    model: two-model
                    base_url: https://two.example
                    api_key: '%s'
                """.formatted(defaultName, firstKey, secondKey);
    }
}
