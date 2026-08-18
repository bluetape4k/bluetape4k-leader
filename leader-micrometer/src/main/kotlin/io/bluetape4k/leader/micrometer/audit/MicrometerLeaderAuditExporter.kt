@file:Suppress(
    "LargeClass",
    "LongMethod",
    "MagicNumber",
    "CyclomaticComplexMethod",
    "TooManyFunctions",
    "TooGenericExceptionCaught",
)

package io.bluetape4k.leader.micrometer.audit

import io.bluetape4k.leader.audit.LeaderAuditExportEvent
import io.bluetape4k.leader.audit.LeaderAuditExportObserver
import io.bluetape4k.leader.audit.LeaderAuditExportSnapshot
import io.bluetape4k.leader.audit.LeaderAuditExporter
import io.bluetape4k.leader.audit.LeaderAuditSubmitResult
import io.bluetape4k.leader.micrometer.MicrometerNames
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.HashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * core audit exporter를 Micrometer의 고정 low-cardinality meter 집합으로 장식합니다.
 *
 * decorator는 생성에 성공하면 delegate를 소유하고 `close()`에서 한 번만 닫습니다.
 * meter는 registry에 남아 stable identity를 유지하지만 close 이후에는 delegate를
 * 참조하지 않습니다. v1 metric은 aggregate snapshot만 알 수 있으므로 `outcome` 외
 * `source`/`transport` 차원은 생성하지 않습니다.
 */
class MicrometerLeaderAuditExporter(
    delegate: LeaderAuditExporter,
    registry: MeterRegistry,
) : LeaderAuditExporter {

    private val registration: Registration
    private val closed = AtomicBoolean(false)

    init {
        registration = try {
            RegistryManagerStore.acquire(registry, delegate)
        } catch (failure: Throwable) {
            closeAfterConstructionFailure(delegate, failure)
            throw failure
        }
    }

    override fun submit(event: LeaderAuditExportEvent): LeaderAuditSubmitResult =
        if (closed.get()) LeaderAuditSubmitResult.DROPPED_CLOSED else registration.delegate.submit(event)

    override fun observe(observer: LeaderAuditExportObserver): AutoCloseable =
        registration.delegate.observe(observer)

    override fun snapshot(): LeaderAuditExportSnapshot = registration.delegate.snapshot()

    override fun close() {
        if (closed.compareAndSet(false, true)) registration.close()
    }

    private fun closeAfterConstructionFailure(delegate: LeaderAuditExporter, failure: Throwable) {
        try {
            delegate.close()
        } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
    }

    private class Registration(
        val manager: RegistryManager,
        val delegate: LeaderAuditExporter,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                manager.close(delegate)
            }
        }
    }

    private object RegistryManagerStore {
        private val lock = Any()
        private val referenceQueue = ReferenceQueue<MeterRegistry>()
        private val managers = HashMap<WeakIdentityKey, RegistryManager>()

        fun acquire(registry: MeterRegistry, delegate: LeaderAuditExporter): Registration =
            synchronized(lock) {
                drainCollectedRegistries()
                val entry = managers.entries.firstOrNull { it.key.get() === registry }
                val manager = entry?.value ?: RegistryManager().also {
                    managers[WeakIdentityKey(registry, referenceQueue)] = it
                }
                try {
                    manager.acquire(registry, delegate)
                } catch (failure: Throwable) {
                    if (manager.isUnused()) {
                        managers.entries.removeIf { it.value === manager }
                    }
                    throw failure
                }
            }

        private fun drainCollectedRegistries() {
            while (true) {
                val collected = referenceQueue.poll() as WeakIdentityKey? ?: return
                managers.remove(collected)
            }
        }
    }

    private class WeakIdentityKey(
        referent: MeterRegistry,
        queue: ReferenceQueue<MeterRegistry>,
    ) : WeakReference<MeterRegistry>(referent, queue) {
        private val identityHash = System.identityHashCode(referent)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean =
            this === other || (other is WeakIdentityKey && get() != null && get() === other.get())
    }

    private class RegistryManager {
        private val lock = Any()
        private val meters = HashMap<MetricDescriptor, Meter>()
        private var activeDelegate: LeaderAuditExporter? = null
        private var offsets = CumulativeValues.ZERO
        private var detachedSnapshot = TerminalSnapshot
        private var closingSnapshot: LeaderAuditExportSnapshot? = null
        private var state = ManagerState.DETACHED
        private var compromised = false
        private var sourceDegraded = false
        private var lastTrustedCumulative = CumulativeValues.ZERO
        private var lastTrustedGauge = GaugeValues(0, 0, false)

        fun isUnused(): Boolean = synchronized(lock) {
            activeDelegate == null && meters.isEmpty() && !compromised
        }

        fun acquire(registry: MeterRegistry, delegate: LeaderAuditExporter): Registration =
            synchronized(lock) {
                check(!compromised) {
                    OWNERSHIP_CONFLICT_MESSAGE
                }
                check(activeDelegate == null) {
                    "A MicrometerLeaderAuditExporter is already active for this MeterRegistry"
                }
                ensureMeters(registry)
                activeDelegate = delegate
                state = ManagerState.OPEN
                sourceDegraded = false
                lastTrustedCumulative = CumulativeValues.ZERO
                lastTrustedGauge = GaugeValues(0, 0, false)
                Registration(this, delegate)
            }

        fun close(delegate: LeaderAuditExporter) {
            var primary: Throwable? = null
            var finalSnapshot: LeaderAuditExportSnapshot? = null
            var closeEntryFailure: Throwable? = null
            synchronized(lock) {
                if (activeDelegate !== delegate || state != ManagerState.OPEN) return
                try {
                    closingSnapshot = delegate.snapshot()
                } catch (failure: Throwable) {
                    closeEntryFailure = failure
                    closingSnapshot = null
                }
                state = ManagerState.CLOSING
            }

            try {
                delegate.close()
            } catch (failure: Throwable) {
                primary = failure
            }
            closeEntryFailure?.let { primary = appendFailure(primary, it) }
            try {
                finalSnapshot = delegate.snapshot()
            } catch (failure: Throwable) {
                primary = appendFailure(primary, failure)
            }

            synchronized(lock) {
                try {
                    val closeEntry = closingSnapshot
                    val final = finalSnapshot
                    if (final != null && closeEntry != null && final.isNotLessThan(closeEntry)) {
                        offsets = offsets + final.cumulativeValues()
                        detachedSnapshot = final.asDetached()
                    } else {
                        markSourceDegraded()
                        offsets = offsets + (closeEntry?.cumulativeValues() ?: CumulativeValues.ZERO)
                        detachedSnapshot = (closeEntry?.asDetached() ?: TerminalSnapshot).asTerminal()
                    }
                } catch (failure: Throwable) {
                    primary = appendFailure(primary, failure)
                    markSourceDegraded()
                    detachedSnapshot = (closingSnapshot?.asDetached() ?: TerminalSnapshot).asTerminal()
                } finally {
                    activeDelegate = null
                    closingSnapshot = null
                    state = ManagerState.DETACHED
                }
            }
            if (primary != null) throw primary
        }

        fun counter(descriptor: MetricDescriptor): Double = synchronized(lock) {
            currentCumulative().value(descriptor.field).toDouble()
        }

        fun gauge(field: SnapshotField): Double = synchronized(lock) {
            currentGauge().value(field)
        }

        private fun ensureMeters(registry: MeterRegistry) {
            METRIC_DESCRIPTORS.forEach { descriptor ->
                val existing = meters[descriptor]
                if (existing != null) {
                    val registered = findMeter(registry, descriptor)
                    check(registered === existing) {
                        compromised = true
                        OWNERSHIP_CONFLICT_MESSAGE
                    }
                    return@forEach
                }
                check(findMeter(registry, descriptor) == null) {
                    compromised = true
                    OWNERSHIP_CONFLICT_MESSAGE
                }
                val created = registerMeter(registry, descriptor)
                check(findMeter(registry, descriptor) === created) {
                    compromised = true
                    OWNERSHIP_CONFLICT_MESSAGE
                }
                meters[descriptor] = created
            }
        }

        private fun registerMeter(
            registry: MeterRegistry,
            descriptor: MetricDescriptor,
        ): Meter = when (descriptor.kind) {
            MetricKind.COUNTER -> FunctionCounter.builder(descriptor.name, this) {
                counter(descriptor)
            }.apply {
                descriptor.outcome?.let { tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, it) }
            }.register(registry)
            MetricKind.GAUGE -> Gauge.builder(descriptor.name, this) {
                gauge(descriptor.field)
            }.apply {
                descriptor.outcome?.let { tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, it) }
            }.register(registry)
        }

        private fun findMeter(registry: MeterRegistry, descriptor: MetricDescriptor): Meter? {
            var search = registry.find(descriptor.name)
            descriptor.outcome?.let {
                search = search.tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, it)
            }
            return search.meter()
        }

        private fun currentCumulative(): CumulativeValues {
            val active = activeDelegate
            return when (state) {
                ManagerState.OPEN -> offsets +
                    (active?.let(::readSnapshot)?.cumulativeValues() ?: lastTrustedCumulative)
                ManagerState.CLOSING -> offsets + (closingSnapshot?.cumulativeValues() ?: CumulativeValues.ZERO)
                ManagerState.DETACHED -> offsets
            }
        }

        private fun currentGauge(): GaugeValues {
            val active = activeDelegate
            return when (state) {
                ManagerState.OPEN -> active?.let(::readSnapshot)?.gaugeValues() ?: lastTrustedGauge
                ManagerState.CLOSING -> closingSnapshot?.gaugeValues() ?: TerminalSnapshot.gaugeValues()
                ManagerState.DETACHED -> detachedSnapshot.gaugeValues()
            }
        }

        private fun readSnapshot(delegate: LeaderAuditExporter): LeaderAuditExportSnapshot? = try {
            delegate.snapshot().also { snapshot ->
                lastTrustedCumulative = snapshot.cumulativeValues()
                lastTrustedGauge = snapshot.gaugeValues()
            }
        } catch (failure: Throwable) {
            markSourceDegraded(failure)
            null
        }

        private fun markSourceDegraded(failure: Throwable? = null) {
            if (!sourceDegraded) {
                sourceDegraded = true
                if (failure == null) {
                    log.warn { SOURCE_DEGRADED_MESSAGE }
                } else {
                    log.warn(failure) { SOURCE_DEGRADED_MESSAGE }
                }
            }
        }
    }

    private enum class ManagerState {
        OPEN,
        CLOSING,
        DETACHED,
    }

    private enum class MetricKind {
        COUNTER,
        GAUGE,
    }

    internal enum class SnapshotField {
        ACCEPTED,
        DROPPED_QUEUE_FULL,
        DROPPED_CLOSED,
        RETRIES,
        FAILURES,
        CANCELLATIONS,
        REJECTIONS,
        OBSERVER_DROPS,
        OBSERVER_REGISTRATION_DROPS,
        DIAGNOSTICS_FAILURES,
        QUEUED,
        IN_FLIGHT,
        DIAGNOSTICS_CLOSED,
    }

    private data class MetricDescriptor(
        val name: String,
        val field: SnapshotField,
        val kind: MetricKind,
        val outcome: String? = null,
    )

    internal data class CumulativeValues(
        val accepted: Long,
        val droppedQueueFull: Long,
        val droppedClosed: Long,
        val retries: Long,
        val failures: Long,
        val cancellations: Long,
        val executorRejections: Long,
        val schedulerRejections: Long,
        val observerDrops: Long,
        val observerRegistrationDrops: Long,
        val diagnosticsFailures: Long,
    ) {
        operator fun plus(other: CumulativeValues): CumulativeValues = CumulativeValues(
            accepted + other.accepted,
            droppedQueueFull + other.droppedQueueFull,
            droppedClosed + other.droppedClosed,
            retries + other.retries,
            failures + other.failures,
            cancellations + other.cancellations,
            executorRejections + other.executorRejections,
            schedulerRejections + other.schedulerRejections,
            observerDrops + other.observerDrops,
            observerRegistrationDrops + other.observerRegistrationDrops,
            diagnosticsFailures + other.diagnosticsFailures,
        )

        internal fun value(field: SnapshotField): Long = when (field) {
            SnapshotField.ACCEPTED -> accepted
            SnapshotField.DROPPED_QUEUE_FULL -> droppedQueueFull
            SnapshotField.DROPPED_CLOSED -> droppedClosed
            SnapshotField.RETRIES -> retries
            SnapshotField.FAILURES -> failures
            SnapshotField.CANCELLATIONS -> cancellations
            SnapshotField.REJECTIONS -> executorRejections + schedulerRejections
            SnapshotField.OBSERVER_DROPS -> observerDrops
            SnapshotField.OBSERVER_REGISTRATION_DROPS -> observerRegistrationDrops
            SnapshotField.DIAGNOSTICS_FAILURES -> diagnosticsFailures
            else -> 0
        }

        companion object {
            val ZERO = CumulativeValues(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        }
    }

    internal data class GaugeValues(
        val queued: Int,
        val inFlight: Int,
        val diagnosticsClosed: Boolean,
    ) {
        internal fun value(field: SnapshotField): Double = when (field) {
            SnapshotField.QUEUED -> queued.toDouble()
            SnapshotField.IN_FLIGHT -> inFlight.toDouble()
            SnapshotField.DIAGNOSTICS_CLOSED -> if (diagnosticsClosed) 1.0 else 0.0
            else -> 0.0
        }
    }

    internal data class DetachedSnapshot(
        val cumulative: CumulativeValues,
        val gauges: GaugeValues,
    ) {
        fun cumulativeValues(): CumulativeValues = cumulative
        fun gaugeValues(): GaugeValues = gauges
        fun asTerminal(): DetachedSnapshot = copy(gauges = GaugeValues(0, 0, true))
    }

    private companion object : KLogging() {
        const val OWNERSHIP_CONFLICT_MESSAGE =
            "MeterRegistry already contains a foreign or compromised leader audit meter"
        const val SOURCE_DEGRADED_MESSAGE = "leader.audit.export.meter-source-degraded"

        val TerminalSnapshot = DetachedSnapshot(
            cumulative = CumulativeValues.ZERO,
            gauges = GaugeValues(0, 0, true),
        )

        val METRIC_DESCRIPTORS = listOf(
            MetricDescriptor(
                MicrometerNames.AUDIT_EXPORT_ACCEPTED,
                SnapshotField.ACCEPTED,
                MetricKind.COUNTER,
                "accepted",
            ),
            MetricDescriptor(
                MicrometerNames.AUDIT_EXPORT_DROPPED,
                SnapshotField.DROPPED_QUEUE_FULL,
                MetricKind.COUNTER,
                "queue_full",
            ),
            MetricDescriptor(
                MicrometerNames.AUDIT_EXPORT_DROPPED,
                SnapshotField.DROPPED_CLOSED,
                MetricKind.COUNTER,
                "closed",
            ),
            MetricDescriptor(
                MicrometerNames.AUDIT_EXPORT_RETRIES,
                SnapshotField.RETRIES,
                MetricKind.COUNTER,
                "retry",
            ),
            MetricDescriptor(
                MicrometerNames.AUDIT_EXPORT_FAILURES,
                SnapshotField.FAILURES,
                MetricKind.COUNTER,
                "failure",
            ),
            MetricDescriptor(
                MicrometerNames.AUDIT_EXPORT_QUEUE_DEPTH,
                SnapshotField.QUEUED,
                MetricKind.GAUGE,
            ),
            MetricDescriptor(
                MicrometerNames.AUDIT_EXPORT_IN_FLIGHT,
                SnapshotField.IN_FLIGHT,
                MetricKind.GAUGE,
            ),
            MetricDescriptor(
                MicrometerNames.AUDIT_EXPORT_CANCELLED,
                SnapshotField.CANCELLATIONS,
                MetricKind.COUNTER,
                "cancelled",
            ),
            MetricDescriptor(
                MicrometerNames.AUDIT_EXPORT_REJECTIONS,
                SnapshotField.REJECTIONS,
                MetricKind.COUNTER,
                "rejected",
            ),
            MetricDescriptor(
                MicrometerNames.AUDIT_EXPORT_OBSERVER_DROPPED,
                SnapshotField.OBSERVER_DROPS,
                MetricKind.COUNTER,
            ),
            MetricDescriptor(
                MicrometerNames.AUDIT_EXPORT_OBSERVER_REGISTRATION_DROPPED,
                SnapshotField.OBSERVER_REGISTRATION_DROPS,
                MetricKind.COUNTER,
            ),
            MetricDescriptor(
                MicrometerNames.AUDIT_EXPORT_DIAGNOSTICS_FAILURES,
                SnapshotField.DIAGNOSTICS_FAILURES,
                MetricKind.COUNTER,
            ),
            MetricDescriptor(
                MicrometerNames.AUDIT_EXPORT_DIAGNOSTICS_CLOSED,
                SnapshotField.DIAGNOSTICS_CLOSED,
                MetricKind.GAUGE,
            ),
        )
    }
}

private fun LeaderAuditExportSnapshot.cumulativeValues(): MicrometerLeaderAuditExporter.CumulativeValues =
    MicrometerLeaderAuditExporter.CumulativeValues(
        accepted = accepted,
        droppedQueueFull = droppedQueueFull,
        droppedClosed = droppedClosed,
        retries = retries,
        failures = terminalFailures,
        cancellations = cancellations,
            executorRejections = executorRejections,
            schedulerRejections = schedulerRejections,
        observerDrops = observerDrops,
        observerRegistrationDrops = observerRegistrationDrops,
        diagnosticsFailures = diagnosticsFatalErrors,
    )

private fun appendFailure(primary: Throwable?, next: Throwable): Throwable {
    if (primary == null) return next
    if (primary !== next) primary.addSuppressed(next)
    return primary
}

private fun LeaderAuditExportSnapshot.gaugeValues(): MicrometerLeaderAuditExporter.GaugeValues =
    MicrometerLeaderAuditExporter.GaugeValues(
        queued = queued,
        inFlight = inFlight,
        diagnosticsClosed = diagnosticsClosed,
    )

private fun LeaderAuditExportSnapshot.isNotLessThan(other: LeaderAuditExportSnapshot): Boolean =
    cumulativeValues().isNotLessThan(other.cumulativeValues())

private fun MicrometerLeaderAuditExporter.CumulativeValues.isNotLessThan(
    other: MicrometerLeaderAuditExporter.CumulativeValues,
): Boolean = listOf(
    accepted >= other.accepted,
    droppedQueueFull >= other.droppedQueueFull,
    droppedClosed >= other.droppedClosed,
    retries >= other.retries,
    failures >= other.failures,
    cancellations >= other.cancellations,
    executorRejections >= other.executorRejections,
    schedulerRejections >= other.schedulerRejections,
    observerDrops >= other.observerDrops,
    observerRegistrationDrops >= other.observerRegistrationDrops,
    diagnosticsFailures >= other.diagnosticsFailures,
).all { it }

private fun LeaderAuditExportSnapshot.asDetached(): MicrometerLeaderAuditExporter.DetachedSnapshot =
    MicrometerLeaderAuditExporter.DetachedSnapshot(
        cumulative = cumulativeValues(),
        gauges = gaugeValues().copy(diagnosticsClosed = true),
    )
