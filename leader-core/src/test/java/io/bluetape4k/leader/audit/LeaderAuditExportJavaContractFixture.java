package io.bluetape4k.leader.audit;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Java compile/run fixture for the stable audit exporter boundary. */
public final class LeaderAuditExportJavaContractFixture {

    private LeaderAuditExportJavaContractFixture() {
    }

    public static boolean exercise() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            Executor executor = Runnable::run;
            LeaderAuditExportOptions options = new LeaderAuditExportOptions(
                8,
                2,
                3,
                Duration.ofSeconds(5),
                Duration.ofMillis(1),
                Duration.ofSeconds(1),
                executor,
                scheduler
            );
            if (options.getQueueCapacity() != 8 || options.getMaxInFlight() != 2) {
                return false;
            }

            LeaderAuditDelivery delivery = event ->
                CompletableFuture.completedFuture(LeaderAuditDeliveryResult.SUCCESS);
            LeaderAuditExportObserver observer = observation -> {
            };
            LeaderAuditExporter exporter = new LeaderAuditExporter() {
                @Override
                public LeaderAuditSubmitResult submit(LeaderAuditExportEvent event) {
                    return LeaderAuditSubmitResult.DROPPED_CLOSED;
                }

                @Override
                public AutoCloseable observe(LeaderAuditExportObserver value) {
                    return () -> {
                    };
                }

                @Override
                public LeaderAuditExportSnapshot snapshot() {
                    return null;
                }

                @Override
                public void close() {
                }
            };

            try (LeaderAuditExporter ignored = exporter) {
                exporter.observe(observer).close();
                exporter.submit(null);
                delivery.deliver(null);
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            scheduler.shutdownNow();
        }
    }
}
