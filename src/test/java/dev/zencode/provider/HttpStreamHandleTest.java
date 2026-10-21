package dev.zencode.provider;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpStreamHandleTest {
    @Test
    void cancellationCompletesLogicallyButWaitsForPhysicalReaderExit() {
        CompletableFuture<Void> request = new CompletableFuture<>();
        HttpStreamHandle handle = new HttpStreamHandle(request, new CompletableFuture<>(), new AtomicReference<>());
        handle.cancel();
        assertTrue(request.isCancelled());
        assertTrue(handle.completion().toCompletableFuture().isDone());
        assertFalse(handle.physicalCompletion().toCompletableFuture().isDone());
        handle.completePhysical();
        assertTrue(handle.physicalCompletion().toCompletableFuture().isDone());
    }

    @Test
    void closesInputAttachedAfterCancellation() {
        HttpStreamHandle handle = handle();
        AtomicInteger closed = new AtomicInteger();
        handle.cancel();
        handle.attachInput(input(closed, false));
        assertEquals(1, closed.get());
        assertFalse(handle.physicalCompletion().toCompletableFuture().isDone());
    }

    @Test
    void cancellationClosesAttachedInputOnceAndIsIdempotent() {
        HttpStreamHandle handle = handle();
        AtomicInteger closed = new AtomicInteger();
        handle.attachInput(input(closed, false));
        handle.cancel();
        handle.cancel();
        assertEquals(1, closed.get());
    }

    @Test
    void inputCloseFailureDoesNotBreakLogicalCancellation() {
        HttpStreamHandle handle = handle();
        AtomicInteger closed = new AtomicInteger();
        handle.attachInput(input(closed, true));
        assertDoesNotThrow(handle::cancel);
        assertEquals(1, closed.get());
        assertTrue(handle.completion().toCompletableFuture().isDone());
        assertFalse(handle.physicalCompletion().toCompletableFuture().isDone());
    }

    @Test
    void physicalExitCompletesLogicalSignalIfConsumerFailedBeforeDoingSo() {
        HttpStreamHandle handle = handle();
        handle.completePhysical();
        handle.completePhysical();
        assertTrue(handle.completion().toCompletableFuture().isDone());
        assertTrue(handle.physicalCompletion().toCompletableFuture().isDone());
    }

    @Test
    void legacyHandlesDefaultToLogicalCompletion() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        StreamHandle handle = new StreamHandle() {
            @Override public void cancel() { done.complete(null); }
            @Override public CompletionStage<Void> completion() { return done; }
        };
        assertSame(done, handle.physicalCompletion());
    }

    private static HttpStreamHandle handle() {
        return new HttpStreamHandle(new CompletableFuture<>(), new CompletableFuture<>(), new AtomicReference<>());
    }

    private static InputStream input(AtomicInteger closed, boolean failClose) {
        return new InputStream() {
            @Override public int read() { return -1; }
            @Override public void close() throws IOException {
                closed.incrementAndGet();
                if (failClose) throw new IOException("synthetic-private-detail");
            }
        };
    }
}
