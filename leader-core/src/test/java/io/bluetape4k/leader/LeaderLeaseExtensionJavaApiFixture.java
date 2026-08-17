package io.bluetape4k.leader;

import java.time.Instant;

/** Java source fixture for the public OBS-02 core facade. */
public final class LeaderLeaseExtensionJavaApiFixture {

    private LeaderLeaseExtensionJavaApiFixture() {
    }

    public static AutoCloseable register() {
        LeaderLeaseExtensionObserver observer = event -> {
        };
        return LeaderLeaseExtensionObservers.addObserver(observer);
    }

    public static boolean registerAndRemove() {
        LeaderLeaseExtensionObserver observer = event -> {
        };
        LeaderLeaseExtensionObservers.addObserver(observer);
        return LeaderLeaseExtensionObservers.removeObserver(observer);
    }

    public static LeaderLeaseExtensionEvent event() {
        return new LeaderLeaseExtensionEvent(
            LeaderLeaseExtensionSource.USER,
            LeaderLeaseExtensionExecution.BLOCKING,
            new ExtendOutcome.Extended(Instant.EPOCH),
            1L,
            null
        );
    }

    public static long droppedCount() {
        return LeaderLeaseExtensionObservers.droppedCount();
    }
}
