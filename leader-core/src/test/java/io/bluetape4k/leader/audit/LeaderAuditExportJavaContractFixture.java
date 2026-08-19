package io.bluetape4k.leader.audit;

import io.bluetape4k.leader.LeaderLease;
import io.bluetape4k.leader.history.LeaderLockHistoryRecord;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
            if (LeaderAuditExportEvent.MAX_INPUT_ATTRIBUTES != 32 ||
                LeaderAuditExportEvent.MAX_INPUT_ATTRIBUTES_TOTAL_BYTES != 8192) {
                return false;
            }
            if (!exerciseKindSanitizer()) {
                return false;
            }
            if (!exerciseFactoryOnlySnapshots()) {
                return false;
            }

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

    private static boolean exerciseKindSanitizer() {
        String single = LeaderAuditValueSanitizer.Default.INSTANCE.sanitize(LeaderAuditField.KIND, "SINGLE");
        if (!"SINGLE".equals(single)) {
            return false;
        }
        String hash = LeaderAuditValueSanitizer.Hash.INSTANCE.sanitize(LeaderAuditField.KIND, "SINGLE");
        if (!"8316f8178707dee9ea8c0e44178b4993a37244112fd60a0be23dae005a3dca01".equals(hash)) {
            return false;
        }
        String truncated = new LeaderAuditValueSanitizer.Truncate(16)
            .sanitize(LeaderAuditField.KIND, "GROUP");
        if (!"GROUP".equals(truncated)) {
            return false;
        }
        String raw = new LeaderAuditValueSanitizer.Raw(
            java.util.Set.of(LeaderAuditField.KIND), 16
        ).sanitize(LeaderAuditField.KIND, "SINGLE");
        if (!"SINGLE".equals(raw)) {
            return false;
        }
        try {
            LeaderAuditValueSanitizer.Default.INSTANCE.sanitize(LeaderAuditField.KIND, "ACQUIRED");
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static boolean exerciseFactoryOnlySnapshots() {
        try {
            for (Class<?> eventType : new Class<?>[] {
                LeaderAuditExportEvent.History.class,
                LeaderAuditExportEvent.Lifecycle.class
            }) {
                Class<?> snapshotType = Class.forName(eventType.getName() + "$Snapshot");
                Constructor<?>[] constructors = snapshotType.getDeclaredConstructors();
                Constructor<?>[] nonSyntheticConstructors = java.util.Arrays.stream(constructors)
                    .filter(constructor -> !constructor.isSynthetic())
                    .toArray(Constructor<?>[]::new);
                if (nonSyntheticConstructors.length != 1 ||
                    !Modifier.isPrivate(nonSyntheticConstructors[0].getModifiers())) {
                    return false;
                }
                for (Constructor<?> constructor : constructors) {
                    if ((Modifier.isPublic(constructor.getModifiers()) || constructor.isSynthetic()) &&
                        java.util.Arrays.stream(constructor.getParameterTypes()).anyMatch(
                            parameterType -> parameterType == String.class ||
                                parameterType == Instant.class ||
                                parameterType == Map.class ||
                                parameterType == LeaderLease.class ||
                                parameterType == LeaderLockHistoryRecord.class)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
