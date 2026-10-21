package dev.zencode.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TeamMultiprocessPersistenceTest {
    @TempDir Path tempDir;

    private Process launch(String mode, String sender) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                TeamProcessWriter.class.getName(), mode, tempDir.toString(), "40", sender)
                .redirectErrorStream(true).start();
    }

    private static void awaitSuccess(Process process) throws Exception {
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "子 JVM 超时");
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }

    @Test
    void independentJvmsAppendMailboxWithUniqueSequence() throws Exception {
        Process one = launch("mail", "m-1");
        Process two = launch("mail", "m-2");
        awaitSuccess(one);
        awaitSuccess(two);
        var messages = new TeamMailbox(tempDir).readAll("target");
        assertEquals(80, messages.size());
        for (int i = 0; i < messages.size(); i++) assertEquals(i + 1L, messages.get(i).seq());
    }

    @Test
    void independentJvmsCreateTasksWithoutLostUpdates() throws Exception {
        Process one = launch("task", "m-1");
        Process two = launch("task", "m-2");
        awaitSuccess(one);
        awaitSuccess(two);
        var tasks = new TeamTaskBoard(tempDir).list();
        assertEquals(80, tasks.size());
        assertEquals(80, tasks.stream().map(TeamTask::id).distinct().count());
    }

    @Test
    void independentJvmsUpdateRosterWithoutOverwritingOthers() throws Exception {
        TeamStore store = new TeamStore(tempDir);
        store.save(new Team("alpha", "lead", 1), List.of());
        Process one = launch("member", "m-1");
        Process two = launch("member", "m-2");
        awaitSuccess(one);
        awaitSuccess(two);
        assertEquals(80, store.load("alpha").orElseThrow().roster().size());
    }
}
