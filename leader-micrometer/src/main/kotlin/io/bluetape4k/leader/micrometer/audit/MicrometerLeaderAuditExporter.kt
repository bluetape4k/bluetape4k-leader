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
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * core audit exporter를 Micrometer의 고정 low-cardinality meter 집합으로 장식합니다.
 *
 * decorator는 생성에 성공하면 delegate를 소유하고 `close()`에서 한 번만 닫습니다.
 * meter는 registry에 남아 stable identity를 유지하지만 close 이후에는 delegate를
 * 참조하지 않습니다. v1 metric은 aggregate snapshot만 알 수 있으므로 `outcome` 외
 * `source`/`transport` 차원은 생성하지 않습니다.
 * wrapper 수명 동안 caller는 fixed meter ID를 직접 제거하거나 재등록하지 않아야 합니다.
 * manager는 acquire 또는 metric read에서 관찰한 identity crossing만 compromised로 잠그며,
 * foreign meter를 제거하지 않습니다. compromised registry의 복구에는 새
 * `MeterRegistry` identity가 필요합니다.
 */
class MicrometerLeaderAuditExporter(
    delegate: LeaderAuditExporter,
    registry: MeterRegistry,
) : LeaderAuditExporter {

    private val registration: Registration
    private val lifecycleState = AtomicReference(LifecycleState.OPEN)
    private val admittedCalls = AtomicInteger(0)
    private val submissionsDrained = AtomicReference<CountDownLatch?>(null)
    private val closeCompleted = CountDownLatch(1)
    private val closeFailure = AtomicReference<Throwable?>(null)

    init {
        registration = try {
            val ownershipToken = Any()
            DelegateOwnershipStore.claim(delegate, ownershipToken)
            try {
                RegistryManagerStore.acquire(registry, delegate, ownershipToken)
            } catch (failure: Throwable) {
                DelegateOwnershipStore.release(delegate, ownershipToken)
                throw failure
            }
        } catch (failure: Throwable) {
            if (failure !is DelegateAlreadyOwnedException) {
                closeAfterConstructionFailure(delegate, failure)
            }
            throw failure
        }
    }

    override fun submit(event: LeaderAuditExportEvent): LeaderAuditSubmitResult {
        if (!admitLifecycleCall()) return LeaderAuditSubmitResult.DROPPED_CLOSED
        return try {
            registration.delegate.submit(event)
        } finally {
            releaseLifecycleCall()
        }
    }

    override fun observe(observer: LeaderAuditExportObserver): AutoCloseable {
        if (!admitLifecycleCall()) return AutoCloseable { }
        return try {
            registration.delegate.observe(observer)
        } finally {
            releaseLifecycleCall()
        }
    }

    override fun snapshot(): LeaderAuditExportSnapshot = registration.delegate.snapshot()

    override fun close() {
        if (lifecycleState.compareAndSet(LifecycleState.OPEN, LifecycleState.CLOSING)) {
            awaitAdmittedCalls()
            try {
                registration.close()
            } catch (failure: Throwable) {
                closeFailure.set(failure)
                throw failure
            } finally {
                lifecycleState.set(LifecycleState.CLOSED)
                closeCompleted.countDown()
            }
        } else {
            awaitCloseCompletion()
            closeFailure.get()?.let { throw it }
        }
    }

    private fun admitLifecycleCall(): Boolean {
        var admitted = lifecycleState.get() == LifecycleState.OPEN
        if (admitted) {
            admittedCalls.incrementAndGet()
            admitted = lifecycleState.get() == LifecycleState.OPEN
            if (!admitted) releaseLifecycleCall()
        }
        return admitted
    }

    private fun releaseLifecycleCall() {
        if (admittedCalls.decrementAndGet() == 0) submissionsDrained.get()?.countDown()
    }

    private fun awaitAdmittedCalls() {
        val drain = CountDownLatch(1)
        submissionsDrained.set(drain)
        if (admittedCalls.get() == 0) drain.countDown()
        awaitUninterruptibly(drain)
    }

    private fun awaitCloseCompletion() {
        awaitUninterruptibly(closeCompleted)
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
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
        private val ownershipToken: Any,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    manager.close(delegate)
                } finally {
                    DelegateOwnershipStore.release(delegate, ownershipToken)
                }
            }
        }
    }

    private object DelegateOwnershipStore {
        private val lock = Any()
        private val referenceQueue = ReferenceQueue<LeaderAuditExporter>()
        private val owners = HashMap<WeakIdentityKey<LeaderAuditExporter>, Any>()

        fun claim(delegate: LeaderAuditExporter, token: Any) = synchronized(lock) {
            drainCollectedDelegates()
            if (owners.keys.any { it.get() === delegate }) throw DelegateAlreadyOwnedException()
            owners[WeakIdentityKey(delegate, referenceQueue)] = token
        }

        fun release(delegate: LeaderAuditExporter, token: Any) = synchronized(lock) {
            owners.entries.removeIf { (key, owner) -> key.get() === delegate && owner === token }
        }

        private fun drainCollectedDelegates() {
            while (true) {
                val collected = referenceQueue.poll() as WeakIdentityKey<LeaderAuditExporter>? ?: return
                owners.remove(collected)
            }
        }
    }

    private object RegistryManagerStore {
        private val lock = Any()
        private val referenceQueue = ReferenceQueue<MeterRegistry>()
        private val managers = HashMap<WeakIdentityKey<MeterRegistry>, RegistryManager>()

        fun acquire(registry: MeterRegistry, delegate: LeaderAuditExporter, ownershipToken: Any): Registration =
            synchronized(lock) {
                drainCollectedRegistries()
                val entry = managers.entries.firstOrNull { it.key.get() === registry }
                val manager = entry?.value ?: RegistryManager().also {
                    managers[WeakIdentityKey(registry, referenceQueue)] = it
                }
                try {
                    manager.acquire(registry, delegate, ownershipToken)
                } catch (failure: Throwable) {
                    if (manager.isUnused()) {
                        managers.entries.removeIf { it.value === manager }
                    }
                    throw failure
                }
            }

        private fun drainCollectedRegistries() {
            while (true) {
                val collected = referenceQueue.poll() as WeakIdentityKey<MeterRegistry>? ?: return
                managers.remove(collected)
            }
        }
    }

    private class WeakIdentityKey<T : Any>(
        referent: T,
        queue: ReferenceQueue<T>,
    ) : WeakReference<T>(referent, queue) {
        private val identityHash = System.identityHashCode(referent)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            val otherKey = other as? WeakIdentityKey<*> ?: return false
            val referent = get() ?: return false
            return referent === otherKey.get()
        }
    }

    private class RegistryManager {
        private val lock = Any()
        private val meters = HashMap<MetricDescriptor, Meter>()
        private var registryReference: WeakReference<MeterRegistry>? = null
        private var metersReady = false
        private var activeDelegate: LeaderAuditExporter? = null
        private var offsets = CumulativeValues.ZERO
        private var detachedSnapshot = TerminalSnapshot
        private var closingSnapshot: LeaderAuditExportSnapshot? = null
        private var state = ManagerState.DETACHED
        private var compromised = false
        private var sourceDegraded = false
        private var lastTrustedCumulative = CumulativeValues.ZERO
        private var lastTrustedGauge = GaugeValues(0, 0, false)
        private var ownershipWarningIssued = false

        fun isUnused(): Boolean = synchronized(lock) {
            activeDelegate == null && meters.isEmpty()
        }

        fun acquire(registry: MeterRegistry, delegate: LeaderAuditExporter, ownershipToken: Any): Registration =
            synchronized(lock) {
                check(!compromised) {
                    OWNERSHIP_CONFLICT_MESSAGE
                }
                activeDelegate?.let { active ->
                    if (active === delegate) throw DelegateAlreadyOwnedException()
                    error("A MicrometerLeaderAuditExporter is already active for this MeterRegistry")
                }
                registryReference = registryReference ?: WeakReference(registry)
                ensureMeters(registry)
                metersReady = true
                activeDelegate = delegate
                state = ManagerState.OPEN
                sourceDegraded = false
                lastTrustedCumulative = CumulativeValues.ZERO
                lastTrustedGauge = GaugeValues(0, 0, false)
                Registration(this, delegate, ownershipToken)
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
                    val trustedCumulative = closeEntry
                        ?.cumulativeValues()
                        ?.takeIf { it.isNotLessThan(lastTrustedCumulative) }
                        ?: lastTrustedCumulative
                    val trustedGauge = closeEntry?.gaugeValues() ?: lastTrustedGauge
                    if (closeEntry == null || !closeEntry.cumulativeValues().isNotLessThan(lastTrustedCumulative)) {
                        markSourceDegraded()
                    }
                    if (final != null && final.cumulativeValues().isNotLessThan(trustedCumulative)) {
                        offsets = offsets + final.cumulativeValues()
                        detachedSnapshot = final.asDetached()
                    } else {
                        markSourceDegraded()
                        offsets = offsets + trustedCumulative
                        detachedSnapshot = DetachedSnapshot(trustedCumulative, trustedGauge).asTerminal()
                    }
                } catch (failure: Throwable) {
                    primary = appendFailure(primary, failure)
                    markSourceDegraded()
                    val fallback = closingSnapshot
                        ?.cumulativeValues()
                        ?.takeIf { it.isNotLessThan(lastTrustedCumulative) }
                        ?: lastTrustedCumulative
                    offsets = offsets + fallback
                    detachedSnapshot = DetachedSnapshot(
                        fallback,
                        closingSnapshot?.gaugeValues() ?: lastTrustedGauge,
                    ).asTerminal()
                } finally {
                    activeDelegate = null
                    closingSnapshot = null
                    state = ManagerState.DETACHED
                }
            }
            if (primary != null) throw primary
        }

        fun counter(descriptor: MetricDescriptor): Double = synchronized(lock) {
            verifyMeterOwnership()
            currentCumulative().value(descriptor.field).toDouble()
        }

        fun gauge(field: SnapshotField): Double = synchronized(lock) {
            verifyMeterOwnership()
            currentGauge().value(field)
        }

        private fun ensureMeters(registry: MeterRegistry) {
            METRIC_DESCRIPTORS.forEach { descriptor ->
                val existing = meters[descriptor]
                if (existing != null) {
                    if (findMeter(registry, descriptor) !== existing) failOwnership()
                    return@forEach
                }
                if (findMeter(registry, descriptor) != null) failOwnership()
                val created = registerMeter(registry, descriptor)
                if (findMeter(registry, descriptor) !== created) failOwnership()
                meters[descriptor] = created
            }
        }

        private fun failOwnership(): Nothing {
            markOwnershipCompromised()
            throw IllegalStateException(OWNERSHIP_CONFLICT_MESSAGE)
        }

        private fun verifyMeterOwnership() {
            if (!metersReady || compromised) return
            val registry = registryReference?.get() ?: return
            if (METRIC_DESCRIPTORS.any { descriptor ->
                    val expected = meters[descriptor] ?: return@any true
                    findMeter(registry, descriptor) !== expected
                }
            ) {
                markOwnershipCompromised()
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
            if (compromised) return detachedSnapshot.cumulativeValues()
            val active = activeDelegate
            return when (state) {
                ManagerState.OPEN -> offsets +
                    (active?.let(::readSnapshot)?.cumulativeValues() ?: lastTrustedCumulative)
                ManagerState.CLOSING -> offsets +
                    (closingSnapshot?.cumulativeValues() ?: lastTrustedCumulative)
                ManagerState.DETACHED -> offsets
            }
        }

        private fun currentGauge(): GaugeValues {
            if (compromised) return detachedSnapshot.gaugeValues()
            val active = activeDelegate
            return when (state) {
                ManagerState.OPEN -> active?.let(::readSnapshot)?.gaugeValues() ?: lastTrustedGauge
                ManagerState.CLOSING -> closingSnapshot?.gaugeValues() ?: lastTrustedGauge
                ManagerState.DETACHED -> detachedSnapshot.gaugeValues()
            }
        }

        private fun readSnapshot(delegate: LeaderAuditExporter): LeaderAuditExportSnapshot? = try {
            delegate.snapshot().also { snapshot ->
                val cumulative = snapshot.cumulativeValues()
                if (!cumulative.isNotLessThan(lastTrustedCumulative)) {
                    markSourceDegraded()
                    return null
                }
                lastTrustedCumulative = cumulative
                lastTrustedGauge = snapshot.gaugeValues()
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            markSourceDegraded()
            null
        }

        private fun markSourceDegraded() {
            if (!sourceDegraded) {
                sourceDegraded = true
                log.warn { SOURCE_DEGRADED_MESSAGE }
            }
        }

        private fun markOwnershipCompromised() {
            if (!compromised) {
                compromised = true
                detachedSnapshot = DetachedSnapshot(
                    cumulative = offsets + lastTrustedCumulative,
                    gauges = lastTrustedGauge,
                ).asTerminal()
            }
            if (!ownershipWarningIssued) {
                ownershipWarningIssued = true
                log.warn { OWNERSHIP_CONFLICT_WARNING }
            }
        }
    }

    private enum class ManagerState {
        OPEN,
        CLOSING,
        DETACHED,
    }

    private enum class LifecycleState {
        OPEN,
        CLOSING,
        CLOSED,
    }

    private class DelegateAlreadyOwnedException : IllegalStateException(
        "The delegate is already owned by an active MicrometerLeaderAuditExporter",
    )

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
        const val OWNERSHIP_CONFLICT_WARNING = "leader.audit.export.meter-ownership-conflict"
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
