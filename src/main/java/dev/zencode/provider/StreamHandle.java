package dev.zencode.provider;

import java.util.concurrent.CompletionStage;

public interface StreamHandle {
    void cancel();
    CompletionStage<Void> completion();

    /**
     * Completes once physical request processing and reader cleanup have finished.
     * Implementations whose cancellation completes logically before cleanup must override this.
     */
    default CompletionStage<Void> physicalCompletion() { return completion(); }
}
