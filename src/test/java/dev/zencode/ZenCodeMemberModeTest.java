package dev.zencode;

import dev.zencode.team.BackendType;
import dev.zencode.team.MemberStatus;
import dev.zencode.team.MessageType;
import dev.zencode.team.Team;
import dev.zencode.team.TeamMember;
import dev.zencode.team.TeamRuntime;
import dev.zencode.team.TeamStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZenCodeMemberModeTest {
    @TempDir Path tempDir;

    private TeamMember member(String id, String name) {
        return new TeamMember(id, name, "explore", MemberStatus.RUNNING,
                tempDir.toString(), BackendType.TMUX, false, false, null, 10, 1);
    }

    @Test
    void finishingExternalMemberUpdatesOnlyThatMemberAndNotifiesLead() {
        TeamStore store = new TeamStore(tempDir);
        Team team = new Team("alpha", "lead", 1);
        TeamMember member = member("m-one", "one");
        TeamMember other = member("m-two", "two");
        store.save(team, List.of(member, other));
        TeamRuntime runtime = new TeamRuntime(team, store.teamDir(team.name()));
        runtime.putMember(member);
        runtime.putMember(other);

        assertTrue(ZenCode.finishIdle(store, runtime, member, null));

        var roster = store.load(team.name()).orElseThrow().roster();
        assertEquals(MemberStatus.IDLE, roster.stream()
                .filter(m -> m.agentId().equals(member.agentId())).findFirst().orElseThrow().status());
        assertEquals(MemberStatus.RUNNING, roster.stream()
                .filter(m -> m.agentId().equals(other.agentId())).findFirst().orElseThrow().status());
        assertEquals(MemberStatus.IDLE, runtime.member(member.name()).orElseThrow().status());
        var messages = runtime.mailbox().readAll("lead");
        assertEquals(1, messages.size());
        assertEquals(MessageType.LIFECYCLE, messages.get(0).type());
        assertEquals("IDLE", messages.get(0).protocol().get("event"));
    }

    @Test
    void terminatedExternalMemberIsNotRevivedOrReportedIdle() {
        TeamStore store = new TeamStore(tempDir);
        Team team = new Team("alpha", "lead", 1);
        TeamMember member = member("m-one", "one");
        store.save(team, List.of(member));
        TeamRuntime runtime = new TeamRuntime(team, store.teamDir(team.name()));
        runtime.putMember(member);
        store.upsertMember(team, member.withStatus(MemberStatus.TERMINATED));

        assertFalse(ZenCode.finishIdle(store, runtime, member, null));

        assertEquals(MemberStatus.TERMINATED,
                store.load(team.name()).orElseThrow().roster().get(0).status());
        assertEquals(MemberStatus.TERMINATED,
                runtime.member(member.name()).orElseThrow().status());
        assertTrue(runtime.mailbox().readAll("lead").isEmpty());
    }

    @Test
    void finishingAfterTeamDeletionDoesNotRecreateTeam() {
        TeamStore store = new TeamStore(tempDir);
        Team team = new Team("alpha", "lead", 1);
        TeamMember member = member("m-one", "one");
        store.save(team, List.of(member));
        TeamRuntime runtime = new TeamRuntime(team, store.teamDir(team.name()));
        runtime.putMember(member);
        assertTrue(store.delete(team.name()));

        assertFalse(ZenCode.finishIdle(store, runtime, member, null));

        assertTrue(store.load(team.name()).isEmpty());
        assertEquals(MemberStatus.RUNNING, runtime.member(member.name()).orElseThrow().status());
    }
}
