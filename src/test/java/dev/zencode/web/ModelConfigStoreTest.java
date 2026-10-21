package dev.zencode.web;

import dev.zencode.cli.CliOptions;
import dev.zencode.config.Protocol;
import dev.zencode.config.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic local configuration only; no Provider or network is constructed. */
class ModelConfigStoreTest {
    @TempDir Path directory;

    private CliOptions options() {
        return new CliOptions(directory.resolve("config.local.yaml"), null, null, false, null, null, null);
    }

    private ModelConfigStore.Edit edit(ModelConfigStore.Settings state, String original, String name, boolean makeDefault) {
        return new ModelConfigStore.Edit(state.revision(), original, name, "openai", "example-model",
                "https://provider.invalid/v1", "synthetic-key-not-a-secret", false, makeDefault);
    }

    @Test void credentialsNeverReachDiskAndRestartRequiresANewKey() throws Exception {
        var store = new ModelConfigStore(options(), null);
        store.save(edit(store.read(), "", "first", true), (config, persist) -> persist.run());
        String saved = Files.readString(options().configPath());
        assertFalse(saved.contains("api_key"));
        assertFalse(saved.contains("synthetic-key-not-a-secret"));
        assertTrue(store.read().providers().getFirst().keyConfigured());
        assertThrows(ModelConfigStore.SettingsException.class, () -> store.revealKey("first"));
        store.close();
        var restarted = new ModelConfigStore(options(), null);
        assertFalse(restarted.read().providers().getFirst().keyConfigured());
        var state = restarted.read();
        assertThrows(ModelConfigStore.SettingsException.class, () -> restarted.save(
                new ModelConfigStore.Edit(state.revision(), "first", "first", "openai", "example-model",
                        "https://provider.invalid/v1", "", false, true)));
    }

    @Test void firstSaveAppliesSelectedProviderWithoutRestart() {
        var store = new ModelConfigStore(options(), null);
        AtomicReference<String> selected = new AtomicReference<>();
        var result = store.save(edit(store.read(), "", "first", true), (config, persist) -> {
            selected.set(config.selectedProvider().name());
            persist.run();
        });
        assertEquals("first", selected.get());
        assertEquals("first", result.activeProvider());
        assertFalse(result.setupRequired());
        assertFalse(result.applyRequired());
        assertFalse(result.restartRequired());
        assertEquals("", result.applicationError());
        assertTrue(result.providers().getFirst().keyConfigured());
        assertFalse(result.toString().contains("synthetic-key-not-a-secret"));
    }

    @Test void explicitSelectionDoesNotChangeNextStartupDefault() {
        var store = new ModelConfigStore(options(), null);
        store.save(edit(store.read(), "", "first", true), (config, persist) -> persist.run());
        var result = store.save(edit(store.read(), "", "second", false), (config, persist) -> {
            assertEquals("second", config.selectedProvider().name());
            persist.run();
        });
        assertEquals("second", result.activeProvider());
        assertEquals("first", result.defaultProvider());
    }

    @Test void rejectedApplicationLeavesDiskAndActiveConfigurationUntouched() throws Exception {
        var store = new ModelConfigStore(options(), null);
        store.save(edit(store.read(), "", "first", true), (config, persist) -> persist.run());
        String before = Files.readString(options().configPath());
        assertThrows(IllegalStateException.class, () -> store.save(
                edit(store.read(), "", "second", true), (config, persist) -> {
                    throw new IllegalStateException("busy");
                }));
        assertEquals(before, Files.readString(options().configPath()));
        assertEquals("first", store.read().activeProvider());
    }

    @Test void startupFailureReportsSavedButNotAppliedWithoutExposingCause() {
        var store = new ModelConfigStore(options(), null);
        var result = store.save(edit(store.read(), "", "first", true), (config, persist) -> {
            persist.run();
            throw new IllegalStateException("synthetic-private-diagnostic");
        });
        assertTrue(result.setupRequired());
        assertTrue(result.applyRequired());
        assertFalse(result.applicationError().isBlank());
        assertFalse(result.applicationError().contains("synthetic-private-diagnostic"));
        assertTrue(Files.exists(options().configPath()));
        var retry = store.save(edit(store.read(), "first", "first", true), (config, persist) -> persist.run());
        assertFalse(retry.setupRequired());
        assertFalse(retry.applyRequired());
        assertEquals("", retry.applicationError());
    }

    @Test void staleRevisionNeverInvokesRuntimeCallback() {
        var store = new ModelConfigStore(options(), null);
        var stale = edit(store.read(), "", "first", true);
        store.save(stale);
        var exception = assertThrows(ModelConfigStore.SettingsException.class,
                () -> store.save(stale, (config, persist) -> fail("must not apply stale edits")));
        assertEquals(409, exception.status());
    }

    @Test void persistedStateDoesNotClaimConnectionWasVerified() {
        var store = new ModelConfigStore(options(), new ProviderConfig("first", Protocol.OPENAI,
                "example-model", URI.create("https://provider.invalid/v1"), "synthetic-key-not-a-secret", false));
        var result = store.save(edit(store.read(), "", "first", true));
        assertTrue(result.applyRequired());
        assertEquals("first", result.activeProvider());
        assertTrue(result.withRuntimeBusy(true).runtimeBusy());
    }
}
