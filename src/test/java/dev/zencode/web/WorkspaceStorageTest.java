package dev.zencode.web;

import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.memory.MemoryConfig;
import dev.zencode.memory.SessionManager;
import dev.zencode.session.SessionMeta;
import dev.zencode.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceStorageTest {
    @TempDir Path directory;

    @Test void plainFolderShowsAddedModifiedAndDeletedContent() throws Exception {
        Files.writeString(directory.resolve("edited.txt"), "old\nkeep\n");
        Files.writeString(directory.resolve("deleted.txt"), "removed\n");
        BrowserChanges changes = new BrowserChanges(directory);
        assertTrue(changes.list().files().isEmpty());
        Files.writeString(directory.resolve("edited.txt"), "new\nkeep\n");
        Files.delete(directory.resolve("deleted.txt"));
        Files.writeString(directory.resolve("added.txt"), "added\n");
        BrowserChanges.ChangeList result = changes.list();
        assertEquals("snapshot", result.source());
        Map<String, String> states = result.files().stream().collect(Collectors.toMap(
                BrowserChanges.ChangedFile::path, BrowserChanges.ChangedFile::worktree));
        assertEquals(Map.of("edited.txt", "M", "deleted.txt", "D", "added.txt", "A"), states);
        assertTrue(changes.preview("edited.txt", "worktree").text().contains("-old\n+new\n"));
        assertTrue(changes.preview("deleted.txt", "worktree").text().contains("-removed\n"));
        assertTrue(changes.preview("added.txt", "worktree").text().contains("+added\n"));
    }

    @Test void newlineOnlyChangeIsVisibleAndPrivateStorageIsExcluded() throws Exception {
        Files.writeString(directory.resolve("text.txt"), "line");
        DirectoryChanges changes = new DirectoryChanges(directory);
        Files.writeString(directory.resolve("text.txt"), "line\n");
        Files.createDirectories(directory.resolve(".zencode"));
        Files.writeString(directory.resolve(".zencode/private.txt"), "synthetic-private-data");
        assertEquals(1, changes.list().files().size());
        String patch = changes.preview("text.txt", "worktree").text();
        assertTrue(patch.contains("-line\n\\ No newline at end of file\n+line\n"));
        assertEquals("unsupported", changes.preview("../outside.txt", "worktree").kind());
        assertEquals("unsupported", changes.preview(".zencode/private.txt", "worktree").kind());
    }

    @Test void binaryContentIsListedWithoutBeingRendered() throws Exception {
        DirectoryChanges changes = new DirectoryChanges(directory);
        Files.write(directory.resolve("binary.bin"), new byte[]{0, 1, 2});
        assertEquals("binary.bin", changes.list().files().getFirst().path());
        assertEquals("unsupported", changes.preview("binary.bin", "worktree").kind());
    }

    @Test void projectsDeduplicateAndRemovalKeepsSourceFiles() throws Exception {
        Path projectDirectory = Files.createDirectory(directory.resolve("project"));
        Files.writeString(projectDirectory.resolve("source.txt"), "source");
        ProjectStore projects = new ProjectStore(directory);
        ProjectStore.Project project = projects.add(projectDirectory);
        assertEquals(project.id(), projects.add(projectDirectory.resolve(".")).id());
        assertEquals(1, projects.list().size());
        projects.rename(project.id(), "renamed");
        assertEquals("renamed", projects.require(project.id()).name());
        projects.remove(project.id());
        assertTrue(projects.list().isEmpty());
        assertEquals("source", Files.readString(projectDirectory.resolve("source.txt")));
    }

    @Test void archiveKeepsManualTitleAndMovedProjectOwnership() throws Exception {
        SessionStore store = new SessionStore(directory);
        Conversation conversation = new Conversation();
        SessionManager manager = new SessionManager(store, MemoryConfig.defaults(), null, conversation);
        manager.setProjectId("original-project");
        String id = manager.start().id();
        store.writeMeta(id, store.readMeta(id).renamed("manual title").inProject("new-project"));
        conversation.commit(ConversationTurn.userTextOnly("automatic title"));
        manager.savePending();
        SessionMeta saved = store.readMeta(id);
        assertEquals("manual title", saved.title());
        assertTrue(saved.manualTitle());
        assertEquals("new-project", saved.projectId());
        assertEquals(1, store.load(id).size());
        assertFalse(Files.exists(directory.resolve("original-project/.zencode")));
    }

    @Test void legacyMetadataStaysARegularChat() throws Exception {
        SessionStore store = new SessionStore(directory);
        String id = store.newSessionId();
        Files.writeString(store.sessionsDir().resolve(id + ".meta"),
                "{\"id\":\"" + id + "\",\"title\":\"legacy\",\"messageCount\":1,\"createdAtMs\":1,\"lastActiveMs\":2}");
        assertNull(store.readMeta(id).projectId());
        assertFalse(store.readMeta(id).manualTitle());
        assertEquals("legacy", store.readMeta(id).title());
    }
}
