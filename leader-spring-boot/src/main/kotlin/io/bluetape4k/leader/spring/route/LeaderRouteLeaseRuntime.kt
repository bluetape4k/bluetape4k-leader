@file:Suppress("ReturnCount", "TooGenericExceptionCaught", "ThrowsCount", "SwallowedException")

package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderLeaseAcquirer
import io.bluetape4k.leader.LeaderLeaseHandle
import io.bluetape4k.leader.LeaderLeaseWatchdogAdmission
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseHandle
import io.bluetape4k.leader.internal.LeaseAdmissionController
import io.bluetape4k.leader.internal.LeaseOperationScheduler
import io.bluetape4k.leader.internal.ResidualLeaseRegistry
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.spring.properties.LeaderRouteLeaseProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * LEASE route가 공유하는 capability와 bounded runtime 상태입니다.
 *
 * 이 객체는 backend token이나 요청 identity를 저장하지 않습니다. shutdown 시에는 신규
 * acquire를 차단하고 기존 handle의 terminal cleanup만 허용합니다.
 */
internal class LeaderRouteLeaseRuntime(
    val acquirer: LeaderLeaseAcquirer,
    val suspendAcquirer: SuspendLeaderLeaseAcquirer?,
    val properties: LeaderRouteLeaseProperties,
    private val configuredOptionsBaseline: LeaderElectionOptions = acquirer.configuredOptions.copy(),
    private val externalObservationSink: SanitizedRouteLeaseObservationSink = NoopRouteLeaseObservationSink,
    private val residualRegistry: ResidualLeaseRegistry = ResidualLeaseRegistry(
        maxResidualLeases = properties.maxResidualLeases,
        retention = properties.drainTimeout.toNanos().nanoseconds,
        maxLeaseLifetime = properties.maxLeaseLifetime.toNanos().nanoseconds,
    ),
    private val lifetimeScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "bluetape4k-route-lease-lifetime").apply { isDaemon = true }
        },
) : AutoCloseable {

    init {
        // 내부 테스트/직접 생성 경로는 인위적으로 짧은 lifetime을 사용할 수 있다.
        // Spring Boot 자동 구성은 외부에서 이미 엄격한 timing budget을 검증한다.
        properties.validateForLeaseMode(enforceTimingBudget = false)
    }

    private val effectiveBlockingWaitTime: Duration = minOf(
        properties.maxBlockingWaitTime.toNanos().nanoseconds,
        configuredOptionsBaseline.waitTime,
    )

    private val metrics = LeaderRouteLeaseMetrics()
    private val admission = LeaseAdmissionController(
        maxConcurrentAcquires = properties.maxConcurrentAcquires,
        maxConcurrentCleanups = properties.maxConcurrentCleanups,
        maxAcquireQueueDepth = properties.maxAcquireQueueDepth,
        maxCleanupQueueDepth = properties.maxCleanupQueueDepth,
        maxMvcBlockingAcquires = properties.maxMvcBlockingAcquires,
        maxActiveLeases = properties.maxActiveLeases,
        maxResidualLeases = properties.maxResidualLeases,
        maxWatchdogInFlight = properties.maxWatchdogInFlight,
    )
    private val acquireScheduler = LeaseOperationScheduler(
        maxInFlight = properties.maxConcurrentAcquires,
        queueCapacity = properties.maxAcquireQueueDepth,
        threadNamePrefix = "bluetape4k-route-lease-acquire",
    )
    private val cleanupScheduler = LeaseOperationScheduler(
        maxInFlight = properties.maxConcurrentCleanups,
        queueCapacity = properties.maxCleanupQueueDepth,
        threadNamePrefix = "bluetape4k-route-lease-cleanup",
    )
    private val emergencyCleanupScheduler = LeaseOperationScheduler(
        maxInFlight = 1,
        queueCapacity = properties.maxResidualLeases,
        threadNamePrefix = "bluetape4k-route-lease-emergency-cleanup",
    )
    private val sharedAcquire = io.bluetape4k.leader.internal.SharedLeaseAcquire(
        scheduler = acquireScheduler,
        acquire = { slot ->
            LeaderLeaseWatchdogAdmission.withProvider(
                admission = { admission.tryReserveWatchdog() },
                block = { acquirer.tryAcquire(slot) },
            )
        },
        reserveAttempt = { admission.tryReserveAcquire() },
        onAdmissionRejected = { observe(LeaseObservationCode.ADMISSION_REJECTED) },
    )
    private val shutdownCoordinator = LeaderRouteLeaseShutdownCoordinator(
        drainTimeout = properties.drainTimeout.toNanos().nanoseconds,
        activeLeases = { admission.activeLeases },
        activeAcquires = { admission.acquireInFlight + sharedAcquire.activeAttempts },
        residualLeases = { maxOf(admission.residualLeases, residualRegistry.size) },
        cleanupComplete = { admission.cleanupInFlight == 0 },
        disposeSchedulers = {
            sharedAcquire.close()
            acquireScheduler.close()
            cleanupScheduler.close()
            emergencyCleanupScheduler.close()
            lifetimeScheduler.shutdownNow()
        },
    )

    fun acceptsAcquire(): Boolean = shutdownCoordinator.acceptsAcquire()

    fun tryAcquire(slot: LeaderSlot): LeaderLeaseHandle? = tryAcquire(slot, mvcBlocking = false)

    @Suppress("LongMethod", "ReturnCount", "TooGenericExceptionCaught", "CyclomaticComplexMethod")
    fun tryAcquire(slot: LeaderSlot, mvcBlocking: Boolean): LeaderLeaseHandle? {
        if (!acceptsAcquire()) {
            observe(LeaseObservationCode.SHUTDOWN)
            return null
        }
        val reservations = reserve(mvcBlocking, sharedAcquire = true) ?: run {
            observe(LeaseObservationCode.ADMISSION_REJECTED)
            return null
        }

        val waitStartedNanos = System.nanoTime()
        val handle = try {
            sharedAcquire.tryAcquire(
                slot,
                effectiveBlockingWaitTime,
            )
        } catch (interrupted: InterruptedException) {
            observe(LeaseObservationCode.TIMEOUT)
            Thread.currentThread().interrupt()
            reservations.releaseAll()
            throw interrupted
        } catch (failure: Throwable) {
            reservations.releaseAll()
            if (failure is Error) throw failure
            observe(LeaseObservationCode.ORDINARY_FAILURE)
            return null
        }
        reservations.acquire?.close()
        if (handle == null) {
            val elapsed = System.nanoTime() - waitStartedNanos
            val waitNanos = effectiveBlockingWaitTime.inWholeNanoseconds
            observe(if (elapsed >= waitNanos) LeaseObservationCode.TIMEOUT else LeaseObservationCode.CONTENTION)
            reservations.releaseAll()
            return null
        }
        lateinit var result: AdmissionBoundLeaseHandle
        result = AdmissionBoundLeaseHandle(
            handle,
            reservations,
            cleanupScheduler,
            emergencyCleanupScheduler,
            lifetimeScheduler,
            properties.maxLeaseLifetime.toNanos().nanoseconds,
            properties.drainTimeout.toNanos().nanoseconds,
            admission,
            ::observe,
            onTerminal = { shutdownCoordinator.unregisterHandle(result) },
        )
        if (!shutdownCoordinator.registerHandle(result) { result.release() }) {
            result.release()
            return null
        }
        if (!result.scheduleLifetime() || result.isTerminal()) {
            result.release()
            return null
        }
        return result
    }

    /** WebFlux suspend 경로도 blocking 경로와 같은 admission/cleanup 경계를 사용합니다. */
    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")
    suspend fun tryAcquireSuspend(slot: LeaderSlot): SuspendLeaderLeaseHandle? {
        if (!acceptsAcquire()) {
            observe(LeaseObservationCode.SHUTDOWN)
            return null
        }
        val reservations = reserve(mvcBlocking = false, sharedAcquire = false) ?: run {
            observe(LeaseObservationCode.ADMISSION_REJECTED)
            return null
        }
        var timedOut = false
        val handle = try {
            withTimeoutOrNull(effectiveBlockingWaitTime) {
                withContext(
                    LeaderLeaseWatchdogAdmission.asContextElement { admission.tryReserveWatchdog() },
                ) {
                    suspendAcquirer?.tryAcquire(slot)
                }
            }.also { if (it == null) timedOut = true }
        } catch (failure: Throwable) {
            reservations.releaseAll()
            when (failure) {
                is java.util.concurrent.CancellationException -> throw failure
                is Error -> throw failure
                else -> {
                    observe(LeaseObservationCode.ORDINARY_FAILURE)
                    return null
                }
            }
        } finally {
            reservations.acquire?.close()
        }
        if (handle == null) {
            observe(if (timedOut) LeaseObservationCode.TIMEOUT else LeaseObservationCode.CONTENTION)
            reservations.releaseAll()
            return null
        }
        lateinit var result: AdmissionBoundSuspendLeaseHandle
        result = AdmissionBoundSuspendLeaseHandle(
            handle = handle,
            reservations = reservations,
            cleanupScheduler = cleanupScheduler,
            emergencyCleanupScheduler = emergencyCleanupScheduler,
            lifetimeScheduler = lifetimeScheduler,
            maxLeaseLifetime = properties.maxLeaseLifetime.toNanos().nanoseconds,
            cleanupTimeout = properties.drainTimeout.toNanos().nanoseconds,
            admission = admission,
            observe = ::observe,
            onTerminal = { shutdownCoordinator.unregisterHandle(result) },
        )
        if (!shutdownCoordinator.registerHandle(result) { result.requestRelease() }) {
            result.requestRelease()
            return null
        }
        if (!result.scheduleLifetime() || result.isTerminal()) {
            result.requestRelease()
            return null
        }
        return result
    }

    val activeLeases: Int get() = admission.activeLeases
    val effectiveActiveCapacity: Int get() = admission.effectiveActiveCapacity
    val acquireInFlight: Int get() = admission.acquireInFlight
    val acquireQueueAvailable: Int get() = admission.acquireQueueAvailable
    val cleanupInFlight: Int get() = admission.cleanupInFlight
    val cleanupQueueAvailable: Int get() = admission.cleanupQueueAvailable
    val watchdogInFlight: Int get() = admission.watchdogInFlight
    val residualLeases: Int get() = maxOf(admission.residualLeases, residualRegistry.size)

    /** Actuator와 테스트가 공유하는 고정 aggregate snapshot입니다. */
    fun diagnostics(): LeaderRouteLeaseDiagnostics = LeaderRouteLeaseDiagnostics(
        runtimeState = runtimeState,
        active = activeLeases,
        effectiveActiveCapacity = effectiveActiveCapacity,
        acquireInFlight = acquireInFlight,
        acquireQueueAvailable = acquireQueueAvailable,
        cleanupInFlight = cleanupInFlight,
        cleanupQueueAvailable = cleanupQueueAvailable,
        watchdogInFlight = watchdogInFlight,
        residual = residualLeases,
        observations = metrics.snapshot().mapKeys { it.key.name },
    )

    /** 관찰 코드와 bounded metric을 같은 sanitized 경계에서 한 번만 발행합니다. */
    fun observe(code: LeaseObservationCode) {
        metrics.observe(code)
        if (externalObservationSink !== metrics) {
            externalObservationSink.observe(code)
        }
    }

    fun resetObservations() = metrics.reset()

    fun quiesce(): Boolean = shutdownCoordinator.quiesce()

    override fun close() {
        shutdownCoordinator.close()
    }

    val runtimeState: String
        get() = shutdownCoordinator.runtimeState.name

    private data class AdmissionReservations(
        val acquire: LeaseAdmissionController.AcquireReservation?,
        val cleanup: LeaseAdmissionController.CleanupReservation,
        val active: LeaseAdmissionController.ActiveReservation,
        val residual: RuntimeResidualReservation,
        val mvcWaiter: LeaseAdmissionController.MvcWaiterReservation?,
    ) {
        fun releaseAll() {
            acquire?.close()
            cleanup.close()
            active.close()
            residual.terminalize()
            mvcWaiter?.close()
        }

        fun transferResidual() {
            cleanup.close()
            active.close()
            residual.transfer()
            mvcWaiter?.close()
        }
    }

    private class RuntimeResidualReservation(
        private val admissionReservation: LeaseAdmissionController.ResidualReservation,
        private val registryReservation: ResidualLeaseRegistry.ResidualReservation,
        private val registry: ResidualLeaseRegistry,
        private val onTransferFailure: () -> Unit,
    ) : io.bluetape4k.leader.LeaseCleanupReservation {
        private val terminal = AtomicBoolean(false)

        override val isTerminal: Boolean get() = terminal.get()

        fun transfer() {
            if (!terminal.get()) {
                val transferred = registry.transfer(
                    reservation = registryReservation,
                    onTerminalized = ::terminalizeFromRegistry,
                )
                if (transferred == null) {
                    // Ownership proof is missing; keep both reservations quarantined
                    // instead of returning a slot as if cleanup had succeeded.
                    onTransferFailure()
                }
            }
        }

        override fun terminalize() {
            if (terminal.compareAndSet(false, true)) {
                registryReservation.terminalize()
                admissionReservation.close()
            }
        }

        private fun terminalizeFromRegistry() {
            terminal.set(true)
            admissionReservation.close()
        }
    }

    private class AdmissionBoundLeaseHandle(
        private val delegate: LeaderLeaseHandle,
        private val reservations: AdmissionReservations,
        private val cleanupScheduler: LeaseOperationScheduler,
        private val emergencyCleanupScheduler: LeaseOperationScheduler,
        private val lifetimeScheduler: ScheduledExecutorService,
        private val maxLeaseLifetime: Duration,
        private val cleanupTimeout: Duration,
        private val admission: LeaseAdmissionController,
        private val observe: (LeaseObservationCode) -> Unit,
        private val onTerminal: () -> Unit,
    ) : LeaderLeaseHandle {

        private val released = AtomicBoolean(false)
        private val terminalStatus = AtomicReference<LeaseOwnershipStatus?>(null)
        private val lifetimeDeadlineNanos = AtomicLong(Long.MAX_VALUE)
        private val lifetime = AtomicReference<ScheduledFuture<*>?>(null)

        override val lockName: String get() = delegate.lockName
        override val auditLeaderId: String get() = delegate.auditLeaderId
        override val acquiredAt: Instant get() = delegate.acquiredAt

        override fun extend(lockAtMostFor: Duration): ExtendOutcome =
            if (released.get() || System.nanoTime() >= lifetimeDeadlineNanos.get()) {
                observe(LeaseObservationCode.STALE)
                ExtendOutcome.NotHeld
            } else {
                val watchdog = admission.tryReserveWatchdog() ?: run {
                    observe(LeaseObservationCode.EXTEND_REJECTED)
                    return ExtendOutcome.Rejected
                }
                try {
                    delegate.extend(lockAtMostFor).also { outcome ->
                        if (outcome == ExtendOutcome.Rejected) observe(LeaseObservationCode.EXTEND_REJECTED)
                        if (outcome == ExtendOutcome.NotHeld) observe(LeaseObservationCode.STALE)
                    }
                } finally {
                    watchdog.close()
                }
            }

        override fun ownershipStatus(): LeaseOwnershipStatus =
            if (released.get()) terminalStatus.get() ?: LeaseOwnershipStatus.UNKNOWN else delegate.ownershipStatus()

        override fun isStillHeld(): Boolean = ownershipStatus() == LeaseOwnershipStatus.HELD

        override fun release() {
            if (!released.compareAndSet(false, true)) return
            lifetime.getAndSet(null)?.cancel(false)
            val terminal = AtomicBoolean(false)
            val cleanupTimeoutFuture = AtomicReference<ScheduledFuture<*>?>(null)
            val cleanup = (cleanupScheduler.submit {
                cleanupDelegate(terminal, cleanupTimeoutFuture)
            } ?: emergencyCleanupScheduler.submit {
                cleanupDelegate(terminal, cleanupTimeoutFuture)
            })
            if (cleanup == null) {
                if (terminal.compareAndSet(false, true)) {
                    observe(LeaseObservationCode.CLEANUP_TIMEOUT)
                    terminalStatus.set(LeaseOwnershipStatus.UNKNOWN)
                    reservations.transferResidual()
                    onTerminal()
                }
            } else {
                val timeout = try {
                    scheduleCleanupTimeout(terminal, cleanup)
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    if (terminal.compareAndSet(false, true)) {
                        observe(LeaseObservationCode.CLEANUP_TIMEOUT)
                        terminalStatus.set(LeaseOwnershipStatus.UNKNOWN)
                        cleanup.cancel(true)
                        reservations.transferResidual()
                        onTerminal()
                    }
                    null
                }
                cleanupTimeoutFuture.set(timeout)
                if (terminal.get()) timeout?.cancel(false)
            }
        }

        fun isTerminal(): Boolean = released.get()

        fun scheduleLifetime(): Boolean {
            lifetimeDeadlineNanos.set(safeDeadline(System.nanoTime(), maxLeaseLifetime.inWholeNanoseconds))
            return try {
                val scheduled = lifetimeScheduler.schedule(
                    {
                        if (!released.get()) {
                            observe(LeaseObservationCode.TIMEOUT)
                            release()
                        }
                    },
                    maxLeaseLifetime.inWholeNanoseconds.coerceAtLeast(1L),
                    TimeUnit.NANOSECONDS,
                )
                if (!lifetime.compareAndSet(null, scheduled) || released.get()) {
                    lifetime.compareAndSet(scheduled, null)
                    scheduled.cancel(false)
                }
                !released.get()
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                false
            }
        }

        private fun cleanupDelegate(
            terminal: AtomicBoolean,
            cleanupTimeoutFuture: AtomicReference<ScheduledFuture<*>?>,
        ) {
            try {
                delegate.release()
                terminal.compareAndSet(false, true)
                terminalStatus.set(LeaseOwnershipStatus.NOT_HELD)
                reservations.releaseAll()
                onTerminal()
            } catch (failure: Throwable) {
                if (terminal.compareAndSet(false, true)) {
                    observe(LeaseObservationCode.BACKEND_ERROR)
                    terminalStatus.set(LeaseOwnershipStatus.UNKNOWN)
                    reservations.transferResidual()
                    onTerminal()
                }
                if (failure is Error) throw failure
            } finally {
                cleanupTimeoutFuture.get()?.cancel(false)
            }
        }

        private fun scheduleCleanupTimeout(
            terminal: AtomicBoolean,
            cleanup: java.util.concurrent.Future<*>?,
        ): ScheduledFuture<*> = lifetimeScheduler.schedule(
            {
                if (terminal.compareAndSet(false, true)) {
                    observe(LeaseObservationCode.CLEANUP_TIMEOUT)
                    terminalStatus.set(LeaseOwnershipStatus.UNKNOWN)
                    cleanup?.cancel(true)
                    reservations.transferResidual()
                    onTerminal()
                }
            },
            cleanupTimeout.inWholeNanoseconds.coerceAtLeast(1L),
            TimeUnit.NANOSECONDS,
        )

        private fun safeDeadline(nowNanos: Long, durationNanos: Long): Long =
            if (durationNanos > 0L && nowNanos > Long.MAX_VALUE - durationNanos) Long.MAX_VALUE
            else nowNanos + durationNanos
    }

    private class AdmissionBoundSuspendLeaseHandle(
        private val handle: SuspendLeaderLeaseHandle,
        private val reservations: AdmissionReservations,
        private val cleanupScheduler: LeaseOperationScheduler,
        private val emergencyCleanupScheduler: LeaseOperationScheduler,
        private val lifetimeScheduler: ScheduledExecutorService,
        private val maxLeaseLifetime: Duration,
        private val cleanupTimeout: Duration,
        private val admission: LeaseAdmissionController,
        private val observe: (LeaseObservationCode) -> Unit,
        private val onTerminal: () -> Unit,
    ) : SuspendLeaderLeaseHandle {
        private val released = AtomicBoolean(false)
        private val lifetimeExpired = AtomicBoolean(false)
        private val terminalStatus = AtomicReference<LeaseOwnershipStatus?>(null)
        private val lifetimeDeadlineNanos = AtomicLong(Long.MAX_VALUE)
        private val lifetime = AtomicReference<ScheduledFuture<*>?>(null)

        override val lockName: String get() = handle.lockName
        override val auditLeaderId: String get() = handle.auditLeaderId
        override val acquiredAt: Instant get() = handle.acquiredAt

        override suspend fun extend(lockAtMostFor: Duration): ExtendOutcome {
            if (released.get() || System.nanoTime() >= lifetimeDeadlineNanos.get()) return ExtendOutcome.NotHeld
            val watchdog = admission.tryReserveWatchdog() ?: run {
                observe(LeaseObservationCode.EXTEND_REJECTED)
                return ExtendOutcome.Rejected
            }
            return try {
                handle.extend(lockAtMostFor)
            } finally {
                watchdog.close()
            }
        }

        override suspend fun ownershipStatus(): LeaseOwnershipStatus =
            if (released.get()) terminalStatus.get() ?: LeaseOwnershipStatus.UNKNOWN else handle.ownershipStatus()

        override suspend fun isStillHeld(): Boolean = ownershipStatus() == LeaseOwnershipStatus.HELD

        private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun requestRelease() {
            lifecycleScope.launch { release() }
        }

        override suspend fun release() = withContext(NonCancellable) {
            if (!released.compareAndSet(false, true)) return@withContext
            lifetime.getAndSet(null)?.cancel(false)
            val cleanupPermit = cleanupScheduler.submit { Unit } ?: emergencyCleanupScheduler.submit { Unit }
            if (cleanupPermit == null) {
                observe(LeaseObservationCode.CLEANUP_TIMEOUT)
                terminalStatus.set(LeaseOwnershipStatus.UNKNOWN)
                reservations.transferResidual()
                onTerminal()
                lifecycleScope.cancel()
                return@withContext
            }
            try {
                val releasedWithinDeadline = withContext(Dispatchers.IO) {
                    cleanupPermit.get(cleanupTimeout.inWholeNanoseconds.coerceAtLeast(1L), TimeUnit.NANOSECONDS)
                    withTimeoutOrNull(cleanupTimeout) {
                        handle.release()
                        true
                    } ?: false
                }
                if (releasedWithinDeadline) {
                    terminalStatus.set(LeaseOwnershipStatus.NOT_HELD)
                    reservations.releaseAll()
                    onTerminal()
                } else {
                    observe(LeaseObservationCode.CLEANUP_TIMEOUT)
                    terminalStatus.set(LeaseOwnershipStatus.UNKNOWN)
                    reservations.transferResidual()
                    onTerminal()
                }
            } catch (_: TimeoutException) {
                observe(LeaseObservationCode.CLEANUP_TIMEOUT)
                terminalStatus.set(LeaseOwnershipStatus.UNKNOWN)
                cleanupPermit.cancel(true)
                reservations.transferResidual()
                onTerminal()
            } catch (cancelled: CancellationException) {
                observe(LeaseObservationCode.CLEANUP_TIMEOUT)
                terminalStatus.set(LeaseOwnershipStatus.UNKNOWN)
                cleanupPermit.cancel(true)
                reservations.transferResidual()
                onTerminal()
                throw cancelled
            } catch (failure: Throwable) {
                observe(LeaseObservationCode.BACKEND_ERROR)
                terminalStatus.set(LeaseOwnershipStatus.UNKNOWN)
                reservations.transferResidual()
                onTerminal()
                if (failure is Error) {
                    lifecycleScope.cancel()
                    throw failure
                }
            }
            lifecycleScope.cancel()
        }

        fun isTerminal(): Boolean = released.get() || lifetimeExpired.get()

        fun scheduleLifetime(): Boolean {
            lifetimeDeadlineNanos.set(safeDeadline(System.nanoTime(), maxLeaseLifetime.inWholeNanoseconds))
            return try {
                val scheduled = lifetimeScheduler.schedule(
                    {
                        if (!released.get() && lifetimeExpired.compareAndSet(false, true)) {
                            observe(LeaseObservationCode.TIMEOUT)
                            lifecycleScope.launch { release() }
                        }
                    },
                    maxLeaseLifetime.inWholeNanoseconds.coerceAtLeast(1L),
                    TimeUnit.NANOSECONDS,
                )
                if (!lifetime.compareAndSet(null, scheduled) || released.get() || lifetimeExpired.get()) {
                    lifetime.compareAndSet(scheduled, null)
                    scheduled.cancel(false)
                }
                !released.get() && !lifetimeExpired.get()
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                false
            }
        }

        private fun safeDeadline(nowNanos: Long, durationNanos: Long): Long =
            if (durationNanos > 0L && nowNanos > Long.MAX_VALUE - durationNanos) Long.MAX_VALUE
            else nowNanos + durationNanos
    }

    @Suppress("CyclomaticComplexMethod")
    private fun reserve(mvcBlocking: Boolean, sharedAcquire: Boolean): AdmissionReservations? {
        val mvcWaiter = if (mvcBlocking) admission.tryReserveMvcWaiter() else null
        if (mvcBlocking && mvcWaiter == null) return null
        val acquire = if (sharedAcquire) null else admission.tryReserveAcquire()
        if (!sharedAcquire && acquire == null) {
            mvcWaiter?.close()
            return null
        }
        val cleanup = admission.tryReserveCleanup() ?: run {
            acquire?.close()
            mvcWaiter?.close()
            return null
        }
        val active = admission.tryReserveActive() ?: run {
            cleanup.close()
            acquire?.close()
            mvcWaiter?.close()
            return null
        }
        val residual = admission.tryReserveResidual() ?: run {
            active.close()
            cleanup.close()
            acquire?.close()
            mvcWaiter?.close()
            return null
        }
        val registryResidual = residualRegistry.tryReserve() ?: run {
            residual.close()
            active.close()
            cleanup.close()
            acquire?.close()
            mvcWaiter?.close()
            return null
        }
        return AdmissionReservations(
            acquire = acquire,
            cleanup = cleanup,
            active = active,
            residual = RuntimeResidualReservation(
                residual,
                registryResidual,
                residualRegistry,
                onTransferFailure = { observe(LeaseObservationCode.BACKEND_ERROR) },
            ),
            mvcWaiter = mvcWaiter,
        )
    }

}
