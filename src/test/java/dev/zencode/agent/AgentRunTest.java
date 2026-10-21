package dev.zencode.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10：有界 AgentRun 测试。覆盖事件顺序、队列背压、满队列取消、
 * 重复取消、重复终止、终止后迟到发布和 completion。
 */
class AgentRunTest {

    @Test
    void normalEventOrderAndSingleTermination() throws Exception {
        AgentRunImpl run = new AgentRunImpl(16);
        run.publish(new AgentEvent.UserMessage("你好"));
        run.publish(new AgentEvent.TextDelta("回复", 1));
        assertTrue(run.publishTerminated(AgentTerminationReason.COMPLETED));

        assertEquals(new AgentEvent.UserMessage("你好"), run.takeEvent());
        assertEquals(new AgentEvent.TextDelta("回复", 1), run.takeEvent());
        AgentEvent.Terminated terminated = (AgentEvent.Terminated) run.takeEvent();
        assertEquals(AgentTerminationReason.COMPLETED, terminated.reason());
        assertEquals(AgentTerminationReason.COMPLETED, run.completion().get(1, TimeUnit.SECONDS));
    }

    @Test
    void takeEventReturnsTerminationWhenQueueEmpty() throws Exception {
        AgentRunImpl run = new AgentRunImpl(4);
        assertTrue(run.publishTerminated(AgentTerminationReason.ERROR));
        assertEquals(AgentTerminationReason.ERROR,
                ((AgentEvent.Terminated) run.takeEvent()).reason());
    }

    @Test
    void pollEventTimesOutWithNull() throws Exception {
        AgentRunImpl run = new AgentRunImpl(4);
        assertNull(run.pollEvent(Duration.ofMillis(50)));
    }

    @Test
    void pollEventReturnsQueuedEvent() throws Exception {
        AgentRunImpl run = new AgentRunImpl(4);
        run.publish(new AgentEvent.UserMessage("查询"));
        assertEquals(new AgentEvent.UserMessage("查询"), run.pollEvent(Duration.ofMillis(100)));
    }

    @Test
    void boundedQueueBackpressureBlocksPublisher() throws Exception {
        AgentRunImpl run = new AgentRunImpl(2);
        assertTrue(run.publish(new AgentEvent.UserMessage("a")));
        assertTrue(run.publish(new AgentEvent.UserMessage("b")));

        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger thirdPublished = new AtomicInteger(0);
        Thread publisher = Thread.ofVirtual().start(() -> {
            boolean accepted = run.publish(new AgentEvent.UserMessage("c"));
            thirdPublished.set(accepted ? 1 : 0);
            released.countDown();
        });

        // 队列满，发布者应阻塞
        Thread.sleep(150);
        assertFalse(released.await(50, TimeUnit.MILLISECONDS));

        // 消费一个后发布者解除阻塞
        run.takeEvent();
        assertTrue(released.await(2, TimeUnit.SECONDS));
        assertEquals(1, thirdPublished.get());
        publisher.join(2000);
        assertFalse(publisher.isAlive());
    }

    @Test
    void cancelObservedEvenWhenQueueFull() throws Exception {
        AgentRunImpl run = new AgentRunImpl(2);
        assertTrue(run.publish(new AgentEvent.UserMessage("a")));
        assertTrue(run.publish(new AgentEvent.UserMessage("b")));

        run.cancel();
        // 保留槽位不受队列容量限制，消费者总能观察到取消终止
        AgentEvent event = run.takeEvent();
        while (!(event instanceof AgentEvent.Terminated)) {
            event = run.takeEvent();
        }
        AgentEvent.Terminated terminated = (AgentEvent.Terminated) event;
        assertEquals(AgentTerminationReason.CANCELLED, terminated.reason());
        assertEquals(AgentTerminationReason.CANCELLED, run.completion().get(1, TimeUnit.SECONDS));
    }

    @Test
    void repeatedCancelInvokesCallbackOnceAndPublishesOnce() throws Exception {
        AtomicInteger callbackCount = new AtomicInteger();
        AgentRunImpl run = new AgentRunImpl(4);
        run.setCancelCallback(callbackCount::incrementAndGet);

        run.cancel();
        run.cancel();
        run.cancel();

        assertEquals(1, callbackCount.get());
        assertEquals(AgentTerminationReason.CANCELLED, run.completion().get(1, TimeUnit.SECONDS));
        long terminatedCount = countTerminated(run);
        assertEquals(1, terminatedCount);
    }

    @Test
    void repeatedTerminationPublishesExactlyOnce() throws Exception {
        AgentRunImpl run = new AgentRunImpl(4);
        assertTrue(run.publishTerminated(AgentTerminationReason.COMPLETED));
        assertFalse(run.publishTerminated(AgentTerminationReason.COMPLETED));
        assertFalse(run.publishTerminated(AgentTerminationReason.ERROR));
        assertEquals(AgentTerminationReason.COMPLETED, run.completion().get(1, TimeUnit.SECONDS));
    }

    @Test
    void publishRejectedAfterTermination() {
        AgentRunImpl run = new AgentRunImpl(4);
        assertTrue(run.publishTerminated(AgentTerminationReason.COMPLETED));
        assertFalse(run.publish(new AgentEvent.TextDelta("迟到", 1)));
        assertFalse(run.publish(new AgentEvent.Terminated(AgentTerminationReason.ERROR, 1)));
    }

    @Test
    void lateTerminalEventRejected() throws Exception {
        AgentRunImpl run = new AgentRunImpl(4);
        assertTrue(run.publishTerminated(AgentTerminationReason.ROUND_LIMIT));
        assertFalse(run.publishTerminated(AgentTerminationReason.ERROR));
        AgentEvent event = run.takeEvent();
        assertEquals(new AgentEvent.Terminated(AgentTerminationReason.ROUND_LIMIT, 0), event);
    }

    @Test
    void completedRoundsRecordedInTermination() throws Exception {
        AgentRunImpl run = new AgentRunImpl(4);
        run.noteRoundCommitted();
        run.noteRoundCommitted();
        run.noteRoundCommitted();
        assertTrue(run.publishTerminated(AgentTerminationReason.COMPLETED));
        AgentEvent.Terminated terminated = (AgentEvent.Terminated) run.takeEvent();
        assertEquals(3, terminated.completedRounds());
    }

    @Test
    void cancelInterruptsBlockedPublisher() throws Exception {
        AgentRunImpl run = new AgentRunImpl(1);
        run.publish(new AgentEvent.UserMessage("a"));

        Thread publisher = Thread.ofVirtual().start(() -> run.publish(new AgentEvent.UserMessage("b")));
        run.setProducerThread(publisher);

        run.cancel();
        publisher.join(2000);
        assertFalse(publisher.isAlive());
        assertEquals(AgentTerminationReason.CANCELLED, run.completion().get(1, TimeUnit.SECONDS));
    }

    @Test
    void consumerDrainsEventsInFifoOrder() throws Exception {
        AgentRunImpl run = new AgentRunImpl(8);
        List<AgentEvent> published = new ArrayList<>(List.of(
                new AgentEvent.UserMessage("你好"),
                new AgentEvent.ThinkingDelta("思考", 1),
                new AgentEvent.TextDelta("文本", 1),
                new AgentEvent.ToolStarted("call-1", "ReadFile", "path=a", 1),
                new AgentEvent.FinalReply("完成")));
        for (AgentEvent event : published) {
            assertTrue(run.publish(event));
        }
        assertTrue(run.publishTerminated(AgentTerminationReason.COMPLETED));

        List<AgentEvent> consumed = new ArrayList<>();
        for (int i = 0; i < published.size() + 1; i++) {
            consumed.add(run.takeEvent());
        }
        assertEquals(published, consumed.subList(0, published.size()));
        assertInstanceOf(AgentEvent.Terminated.class, consumed.get(consumed.size() - 1));
    }

    private static long countTerminated(AgentRunImpl run) throws Exception {
        long count = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            AgentEvent event = run.pollEvent(Duration.ofMillis(20));
            if (event == null) break;
            if (event instanceof AgentEvent.Terminated) {
                count++;
                break;
            }
        }
        return count;
    }
}
