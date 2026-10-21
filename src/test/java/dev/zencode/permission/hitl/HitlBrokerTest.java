package dev.zencode.permission.hitl;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitlBrokerTest {

    @Test
    void askBlocksUntilResolved() throws Exception {
        HitlBroker broker = new HitlBroker();
        HitlRequest request = new HitlRequest("c1", "Bash", "del x", "命中询问规则");
        CompletableFuture<HitlDecision> result = new CompletableFuture<>();
        Thread t = Thread.ofVirtual().start(() -> result.complete(broker.ask(request)));
        // 等待挂起注册
        Thread.sleep(50);
        assertTrue(broker.pendingCount() > 0);
        assertTrue(broker.resolve("c1", HitlDecision.ALLOW_ONCE));
        HitlDecision decision = result.get(2, TimeUnit.SECONDS);
        assertEquals(HitlDecision.ALLOW_ONCE, decision);
        assertEquals(0, broker.pendingCount());
        t.join();
    }

    @Test
    void resolveUnknownCallIdReturnsFalse() {
        HitlBroker broker = new HitlBroker();
        assertFalse(broker.resolve("missing", HitlDecision.DENY));
    }

    @Test
    void askTimesOutToDeny() throws Exception {
        HitlBroker broker = new HitlBroker(200_000_000L); // 200ms
        HitlRequest request = new HitlRequest("c2", "Bash", "x", "超时");
        assertEquals(HitlDecision.DENY, broker.ask(request));
        assertEquals(0, broker.pendingCount());
    }

    @Test
    void doubleResolveOnlyFirstWins() throws Exception {
        HitlBroker broker = new HitlBroker();
        HitlRequest request = new HitlRequest("c3", "Bash", "x", "双解");
        CompletableFuture<HitlDecision> result = new CompletableFuture<>();
        Thread t = Thread.ofVirtual().start(() -> result.complete(broker.ask(request)));
        Thread.sleep(50);
        assertTrue(broker.resolve("c3", HitlDecision.ALLOW_SESSION));
        assertFalse(broker.resolve("c3", HitlDecision.DENY));
        assertEquals(HitlDecision.ALLOW_SESSION, result.get(2, TimeUnit.SECONDS));
        t.join();
    }

    @Test
    void askNotifiesRequestListenerBeforeBlocking() throws Exception {
        HitlBroker broker = new HitlBroker();
        AtomicReference<HitlRequest> notified = new AtomicReference<>();
        broker.setRequestListener(notified::set);

        HitlRequest request = new HitlRequest("c4", "WriteFile", "path=cli.md", "规则与权限模式均未自动决策");
        CompletableFuture<HitlDecision> result = new CompletableFuture<>();
        Thread t = Thread.ofVirtual().start(() -> result.complete(broker.ask(request)));

        long deadline = System.currentTimeMillis() + 2000;
        while (notified.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertNotNull(notified.get(), "ask 挂起前必须通知监听器渲染确认框");
        assertEquals("c4", notified.get().callId());
        assertEquals("WriteFile", notified.get().toolName());

        assertTrue(broker.resolve("c4", HitlDecision.ALLOW_ONCE));
        assertEquals(HitlDecision.ALLOW_ONCE, result.get(2, TimeUnit.SECONDS));
        t.join();
    }

    @Test
    void listenerExceptionDoesNotBreakAsk() throws Exception {
        HitlBroker broker = new HitlBroker();
        broker.setRequestListener(request -> { throw new IllegalStateException("渲染失败"); });
        HitlRequest request = new HitlRequest("c5", "Bash", "x", "渲染失败");
        CompletableFuture<HitlDecision> result = new CompletableFuture<>();
        Thread t = Thread.ofVirtual().start(() -> result.complete(broker.ask(request)));
        Thread.sleep(50);
        assertTrue(broker.resolve("c5", HitlDecision.DENY));
        assertEquals(HitlDecision.DENY, result.get(2, TimeUnit.SECONDS));
        t.join();
    }
}