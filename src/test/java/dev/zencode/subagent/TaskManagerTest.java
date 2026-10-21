package dev.zencode.subagent;

import dev.zencode.chat.Usage;
import dev.zencode.agent.AgentRun;
import dev.zencode.agent.AgentEvent;
import dev.zencode.agent.AgentTerminationReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TaskManagerTest {

    @TempDir
    Path tempDir;

    private static SubagentResult ok(String output) {
        return new SubagentResult("t", TaskStatus.COMPLETED, output, Usage.unknown());
    }

    private static void awaitStatus(TaskManager tm, String id, TaskStatus expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            TaskState s = tm.find(id).orElseThrow();
            if (s.status() == expected) return;
            Thread.sleep(20);
        }
        fail("状态未在超时前变为 " + expected);
    }

    @Test
    void submit后台执行从运行到完成() throws Exception {
        TaskManager tm = new TaskManager();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String id = tm.submit("分析任务", () -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok("完成结果");
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(TaskStatus.RUNNING, tm.find(id).orElseThrow().status());
        release.countDown();
        awaitStatus(tm, id, TaskStatus.COMPLETED);
        assertEquals("完成结果", tm.find(id).orElseThrow().result());
        assertNotNull(tm.find(id).orElseThrow().endTime());
    }

    @Test
    void cancel终止运行中任务() throws Exception {
        TaskManager tm = new TaskManager();
        CountDownLatch latch = new CountDownLatch(1);
        String id = tm.submit("长任务", () -> {
            latch.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok("不该返回");
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(tm.cancel(id));
        assertEquals(TaskStatus.CANCELLED, tm.find(id).orElseThrow().status());
        assertFalse(tm.cancel(id), "已终止任务不能再次取消");
    }

    @Test
    void 自动超时标记TIMEOUT() throws Exception {
        TaskManager tm = new TaskManager(null, Duration.ofMillis(200));
        String id = tm.submit("慢任务", () -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok("不该返回");
        });
        awaitStatus(tm, id, TaskStatus.TIMEOUT);
        assertTrue(tm.find(id).orElseThrow().result().contains("超时"));
    }

    @Test
    void 完成通知回调被触发() throws Exception {
        AtomicReference<TaskState> notified = new AtomicReference<>();
        TaskManager tm = new TaskManager(notified::set, null);
        String id = tm.submit("通知任务", () -> ok("结果"));
        awaitStatus(tm, id, TaskStatus.COMPLETED);
        assertNotNull(notified.get());
        assertEquals(id, notified.get().id());
    }

    @Test
    void list返回所有任务() throws Exception {
        TaskManager tm = new TaskManager();
        String a = tm.submit("任务A", () -> ok("A"));
        String b = tm.submit("任务B", () -> ok("B"));
        awaitStatus(tm, a, TaskStatus.COMPLETED);
        awaitStatus(tm, b, TaskStatus.COMPLETED);
        assertEquals(2, tm.list().size());
    }

    @Test
    void cancelInterruptsWorkerAndCancelsAgentRunBeforeFileWrite() throws Exception {
        AtomicReference<Boolean> providerCancelled = new AtomicReference<>(false);
        CountDownLatch started = new CountDownLatch(1);
        Path output = tempDir.resolve("should-not-write.txt");
        TaskManager manager = new TaskManager();
        String id = manager.submitWithRun("可取消任务", onRun -> {
            onRun.accept(fakeRun(providerCancelled));
            started.countDown();
            try {
                Thread.sleep(10_000);
                Files.writeString(output, "unexpected");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return ok("结束");
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(manager.cancel(id));
        manager.find(id).orElseThrow().worker().join(5000);
        assertEquals(TaskStatus.CANCELLED, manager.find(id).orElseThrow().status());
        assertEquals(Boolean.TRUE, providerCancelled.get());
        assertFalse(Files.exists(output));
    }

    @Test
    void timeoutAlsoCancelsProviderAndInterruptsWorker() throws Exception {
        AtomicReference<Boolean> providerCancelled = new AtomicReference<>(false);
        TaskManager manager = new TaskManager(null, Duration.ofMillis(100));
        String id = manager.submitWithRun("超时任务", onRun -> {
            onRun.accept(fakeRun(providerCancelled));
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok("结束");
        });
        awaitStatus(manager, id, TaskStatus.TIMEOUT);
        manager.find(id).orElseThrow().worker().join(5000);
        assertEquals(Boolean.TRUE, providerCancelled.get());
    }

    private static AgentRun fakeRun(AtomicReference<Boolean> cancelled) {
        return new AgentRun() {
            public AgentEvent takeEvent() { return null; }
            public AgentEvent pollEvent(Duration timeout) { return null; }
            public void cancel() { cancelled.set(true); }
            public boolean publish(AgentEvent event) { return false; }
            public CompletableFuture<AgentTerminationReason> completion() { return new CompletableFuture<>(); }
        };
    }
}
