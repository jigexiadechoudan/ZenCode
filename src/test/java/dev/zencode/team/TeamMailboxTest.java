package dev.zencode.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 邮箱系统测试：分文件存储、seq 追加、marker 已读推进、广播、协议消息、并发、持久化。
 */
class TeamMailboxTest {

    @TempDir
    Path tempDir;

    private TeamMailbox newMailbox() {
        return new TeamMailbox(tempDir.resolve("team"));
    }

    @Test
    void sendAndReadNewDeliversInOrder() {
        TeamMailbox mailbox = newMailbox();
        mailbox.send("lead", "m-1", MessageType.TEXT, "任务", "做 X", Map.of());
        mailbox.send("lead", "m-1", MessageType.TEXT, "补充", "再做 Y", Map.of());

        List<TeamMessage> fresh = mailbox.readNew("m-1");
        assertEquals(2, fresh.size());
        assertEquals(1L, fresh.get(0).seq());
        assertEquals("任务", fresh.get(0).subject());
        assertEquals(2L, fresh.get(1).seq());
        assertEquals("做 X", fresh.get(0).body());
    }

    @Test
    void markerAdvancesAfterRead() {
        TeamMailbox mailbox = newMailbox();
        mailbox.send("lead", "m-1", MessageType.TEXT, "任务", "做 X", Map.of());
        assertEquals(1, mailbox.readNew("m-1").size());
        // 第二次读取无新消息
        assertTrue(mailbox.readNew("m-1").isEmpty());
        assertEquals(0, mailbox.pendingCount("m-1"));
        // 新消息到达后可读
        mailbox.send("lead", "m-1", MessageType.TEXT, "任务2", "做 Y", Map.of());
        assertEquals(1, mailbox.readNew("m-1").size());
    }

    @Test
    void pendingCountDoesNotAdvanceMarker() {
        TeamMailbox mailbox = newMailbox();
        mailbox.send("lead", "m-1", MessageType.TEXT, "任务", "做 X", Map.of());
        assertEquals(1, mailbox.pendingCount("m-1"));
        assertEquals(1, mailbox.pendingCount("m-1"));
        assertFalse(mailbox.readNew("m-1").isEmpty());
    }

    @Test
    void messagesAreStoredPerAgentId() {
        TeamMailbox mailbox = newMailbox();
        mailbox.send("lead", "m-1", MessageType.TEXT, "A", "给 A", Map.of());
        mailbox.send("lead", "m-2", MessageType.TEXT, "B", "给 B", Map.of());
        assertEquals(1, mailbox.readNew("m-1").size());
        assertEquals(1, mailbox.readNew("m-2").size());
        assertTrue(Files.exists(tempDir.resolve("team").resolve("mailbox").resolve("m-1").resolve("messages.jsonl")));
        assertTrue(Files.exists(tempDir.resolve("team").resolve("mailbox").resolve("m-2").resolve("messages.jsonl")));
    }

    @Test
    void broadcastWritesAllTargets() {
        TeamMailbox mailbox = newMailbox();
        mailbox.broadcast("lead", List.of("m-1", "m-2"), MessageType.BROADCAST, "全员", "开工", Map.of());
        assertEquals(1, mailbox.readNew("m-1").size());
        assertEquals(1, mailbox.readNew("m-2").size());
    }

    @Test
    void protocolMessagesRoundTrip() {
        TeamMailbox mailbox = newMailbox();
        mailbox.send("m-1", "lead", MessageType.LIFECYCLE, "队员空闲", "已完成",
                Map.of("event", "IDLE"));
        mailbox.send("lead", "m-1", MessageType.APPROVAL, "审批", "允许执行",
                Map.of("request", "reply", "tool", "Bash", "allow", "true"));

        List<TeamMessage> fresh = mailbox.readNew("lead");
        assertEquals(1, fresh.size());
        assertEquals(MessageType.LIFECYCLE, fresh.get(0).type());
        assertEquals("IDLE", fresh.get(0).protocol().get("event"));

        List<TeamMessage> memberFresh = mailbox.readNew("m-1");
        assertEquals(MessageType.APPROVAL, memberFresh.get(0).type());
        assertEquals("true", memberFresh.get(0).protocol().get("allow"));
    }

    @Test
    void corruptLinesAreSkipped() throws Exception {
        TeamMailbox mailbox = newMailbox();
        mailbox.send("lead", "m-1", MessageType.TEXT, "任务", "做 X", Map.of());
        Path file = tempDir.resolve("team").resolve("mailbox").resolve("m-1").resolve("messages.jsonl");
        Files.writeString(file, Files.readString(file) + "not json\n");
        List<TeamMessage> fresh = mailbox.readNew("m-1");
        assertEquals(1, fresh.size());
    }

    @Test
    void concurrentAppendsLoseNoMessages() throws Exception {
        TeamMailbox mailbox = newMailbox();
        int threads = 8;
        int perThread = 25;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            final int id = t;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    mailbox.send("m-" + id, "m-1", MessageType.TEXT, "并发", "消息", Map.of());
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        List<TeamMessage> all = mailbox.readAll("m-1");
        assertEquals(threads * perThread, all.size(), "并发追加不应丢消息");
        // seq 连续无重复
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 1L, all.get(i).seq(), "seq 应连续");
        }
    }

    @Test
    void unreadPersistsAcrossInstances() {
        Path teamDir = tempDir.resolve("team");
        new TeamMailbox(teamDir).send("lead", "m-1", MessageType.TEXT, "任务", "做 X", Map.of());
        // 新实例（模拟重启）未读仍在
        TeamMailbox restarted = new TeamMailbox(teamDir);
        assertEquals(1, restarted.pendingCount("m-1"));
        assertEquals(1, restarted.readNew("m-1").size());
    }

    @Test
    void sendRejectsBlankRecipient() {
        TeamMailbox mailbox = newMailbox();
        assertThrows(TeamException.class,
                () -> mailbox.send("lead", "", MessageType.TEXT, "x", "y", Map.of()));
    }
}
