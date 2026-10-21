package dev.zencode.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 团队配置持久化测试。
 */
class TeamStoreTest {

    @TempDir
    Path tempDir;

    private Team newTeam(String name) {
        return new Team(name, "lead", 1000L);
    }

    private TeamMember newMember(String name) {
        return new TeamMember("m-abc", name, "explore", MemberStatus.IDLE,
                tempDir.resolve("work").toString(), BackendType.INPROCESS,
                false, false, null, 20, 2000L);
    }

    @Test
    void saveAndLoadRoundTrip() {
        TeamStore store = new TeamStore(tempDir);
        Team team = newTeam("alpha");
        TeamMember member = newMember("explorer");
        store.save(team, List.of(member));

        Optional<TeamStore.StoredTeam> loaded = store.load("alpha");
        assertTrue(loaded.isPresent());
        assertEquals("alpha", loaded.get().team().name());
        assertEquals("lead", loaded.get().team().leadId());
        assertEquals(1000L, loaded.get().team().createdAt());
        assertEquals(1, loaded.get().roster().size());
        TeamMember restored = loaded.get().roster().get(0);
        assertEquals("m-abc", restored.agentId());
        assertEquals("explorer", restored.name());
        assertEquals(MemberStatus.IDLE, restored.status());
        assertEquals(BackendType.INPROCESS, restored.backend());
        assertEquals(tempDir.resolve("work").toString(), restored.workDir());
    }

    @Test
    void loadMissingReturnsEmpty() {
        TeamStore store = new TeamStore(tempDir);
        assertTrue(store.load("nope").isEmpty());
    }

    @Test
    void corruptJsonReturnsEmpty() throws Exception {
        TeamStore store = new TeamStore(tempDir);
        Path dir = store.teamDir("alpha");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("team.json"), "{ not json");
        assertTrue(store.load("alpha").isEmpty());
    }

    @Test
    void listReturnsAllTeams() {
        TeamStore store = new TeamStore(tempDir);
        store.save(newTeam("beta"), List.of());
        store.save(newTeam("gamma"), List.of());
        List<Team> teams = store.list();
        assertEquals(2, teams.size());
        assertTrue(teams.stream().anyMatch(t -> t.name().equals("beta")));
        assertTrue(teams.stream().anyMatch(t -> t.name().equals("gamma")));
    }

    @Test
    void deleteRemovesDirectory() {
        TeamStore store = new TeamStore(tempDir);
        store.save(newTeam("delta"), List.of());
        assertTrue(Files.exists(store.teamDir("delta")));
        assertTrue(store.delete("delta"));
        assertFalse(Files.exists(store.teamDir("delta")));
        assertTrue(store.list().isEmpty());
    }

    @Test
    void deleteMissingReturnsFalse() {
        TeamStore store = new TeamStore(tempDir);
        assertFalse(store.delete("nope"));
    }

    @Test
    void teamDirRejectsTraversal() {
        TeamStore store = new TeamStore(tempDir);
        assertThrows(TeamException.class, () -> store.teamDir("../evil"));
        assertThrows(TeamException.class, () -> store.teamDir("a/b"));
        assertThrows(TeamException.class, () -> store.teamDir("C:\\evil"));
        assertThrows(TeamException.class, () -> store.teamDir(".."));
    }
}
