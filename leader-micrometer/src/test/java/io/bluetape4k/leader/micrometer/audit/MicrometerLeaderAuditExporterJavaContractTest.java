package io.bluetape4k.leader.micrometer.audit;

import io.bluetape4k.leader.audit.LeaderAuditExportEvent;
import io.bluetape4k.leader.audit.LeaderAuditExportObserver;
import io.bluetape4k.leader.audit.LeaderAuditExportSnapshot;
import io.bluetape4k.leader.audit.LeaderAuditExporter;
import io.bluetape4k.leader.audit.LeaderAuditSubmitResult;
import io.bluetape4k.leader.audit.LeaderAuditValueSanitizer;
import io.bluetape4k.leader.LeaderElectionEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Collections;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Java compile/run fixture for the Micrometer audit decorator ABI. */
public final class MicrometerLeaderAuditExporterJavaContractTest {

    private MicrometerLeaderAuditExporterJavaContractTest() {
    }

    public static boolean exercise() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        LeaderAuditExporter delegate = new LeaderAuditExporter() {
            @Override
            public LeaderAuditSubmitResult submit(LeaderAuditExportEvent event) {
                return LeaderAuditSubmitResult.ACCEPTED;
            }

            @Override
            public AutoCloseable observe(LeaderAuditExportObserver observer) {
                return () -> {
                };
            }

            @Override
            public LeaderAuditExportSnapshot snapshot() {
                try {
                    return MicrometerLeaderAuditExporterJavaContractTest.snapshot();
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            }

            @Override
            public void close() {
            }
        };

        try (MicrometerLeaderAuditExporter exporter =
                 new MicrometerLeaderAuditExporter(delegate, registry)) {
            exporter.submit(LeaderAuditExportEvent.Lifecycle.Companion.from(
                new LeaderElectionEvent.Elected("java-contract"),
                Collections.emptyMap(),
                LeaderAuditValueSanitizer.Default.INSTANCE
            ));
            try (AutoCloseable ignored = exporter.observe(observation -> {
            })) {
                // Java try-with-resources exercises the AutoCloseable observer handle.
            }
            LeaderAuditExportSnapshot snapshot = exporter.snapshot();
            return snapshot != null && snapshot.getQueued() == 0 && !snapshot.getClosed();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static LeaderAuditExportSnapshot snapshot() throws ReflectiveOperationException {
        Field companionField = LeaderAuditExportSnapshot.class.getDeclaredField("Companion");
        companionField.setAccessible(true);
        Object companion = companionField.get(null);
        Method create = companion.getClass().getDeclaredMethod(
            "create$io_github_bluetape4k_leader_bluetape4k_leader_core",
            int.class,
            int.class,
            int.class,
            int.class,
            long.class,
            long.class,
            long.class,
            long.class,
            long.class,
            long.class,
            long.class,
            long.class,
            long.class,
            long.class,
            long.class,
            boolean.class,
            boolean.class
        );
        create.setAccessible(true);
        return (LeaderAuditExportSnapshot) create.invoke(
            companion,
            0, 0, 0, 0,
            0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            false, false
        );
    }
}
