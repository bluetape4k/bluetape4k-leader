package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLeaseDefaults
import io.bluetape4k.leader.LeaseCleanupReservation
import io.bluetape4k.leader.LeaseCleanupResult
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import io.bluetape4k.support.requirePositiveNumber

/** Residual entry lifecycle.  Unknown entries remain quarantined until proof returns. */
enum class ResidualLeaseState {
    ACTIVE,
    QUARANTINED_UNKNOWN,
    EVICTED,
}

/**
 * request/context보다 오래 살아남는 cleanup reservation을 process 범위에서 bounded하게 소유합니다.
 *
 * reservation은 transfer 전에 계수하므로 이미 소유한 slot의 transfer는 registry가 가득 찼다는
 * 이유만으로 실패하지 않습니다. cap에서는 새 reservation만 거부합니다.
 */
class ResidualLeaseRegistry(
    maxResidualLeases: Int = LeaderLeaseDefaults.maxResidualLeases,
    private val retention: Duration = LeaderLeaseDefaults.PUBLIC_RELEASE_TIMEOUT,
    private val maxLeaseLifetime: Duration = Duration.INFINITE,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {

    private val lock = ReentrantLock()
    private val reservations = IdentityHashMap<ResidualReservation, ResidualEntry?>()
    private val entries = LinkedHashSet<ResidualEntry>()
    private val capacity = maxResidualLeases.also {
        it.requirePositiveNumber("maxResidualLeases")
    }

    private var rejected = 0
    private var expiredUnknown = 0

    val maxResidualLeases: Int get() = capacity
    val activeCount: Int get() = lock.withLock { entries.count { it.state != ResidualLeaseState.EVICTED } }
    val residualRejected: Int get() = lock.withLock { rejected }
    val residualExpiredUnknown: Int get() = lock.withLock { expiredUnknown }
    val size: Int get() = activeCount

    /** backend acquire를 시작하기 전에 residual slot 하나를 예약합니다. */
    fun tryReserve(): ResidualReservation? = lock.withLock {
        if (reservations.size >= capacity) {
            rejected++
            return null
        }
        ResidualReservation(this).also { reservations[it] = null }
    }

    /**
     * 이미 예약한 slot을 residual entry로 넘깁니다. 반복 호출은 같은 entry를 반환하며
     * slot을 추가로 소비하지 않습니다.
     */
    fun transfer(
        reservation: LeaseCleanupReservation,
        acquiredAtNanos: Long = monotonicNanos(),
        transferAtNanos: Long = monotonicNanos(),
        originContextGeneration: Long = 0L,
        fencingProofAvailable: Boolean = false,
        ttlProofAvailable: Boolean = false,
        onTerminalized: (() -> Unit)? = null,
    ): ResidualEntry? = lock.withLock {
        val residualReservation = reservation as? ResidualReservation ?: return@withLock null
        if (residualReservation.owner !== this) return@withLock null
        val existing = reservations[residualReservation]
        if (existing != null) return@withLock existing
        residualReservation.registerTerminalizer(onTerminalized)
        val entry = ResidualEntry(
            reservation = residualReservation,
            acquiredAtNanos = acquiredAtNanos,
            transferAtNanos = transferAtNanos,
            retentionDeadlineNanos = retentionDeadline(acquiredAtNanos, transferAtNanos),
            originContextGeneration = originContextGeneration,
            fencingProofAvailable = fencingProofAvailable,
            ttlProofAvailable = ttlProofAvailable,
        )
        reservations[residualReservation] = entry
        entries += entry
        entry
    }

    /** residual backend 결과를 조정하고 이번 호출이 terminalization을 얻었는지 반환합니다. */
    fun reconcile(entry: ResidualEntry, result: LeaseCleanupResult): Boolean = lock.withLock {
        if (entry.state == ResidualLeaseState.EVICTED) return@withLock false
        if (entry !in entries) return@withLock false
        entry.terminalResult = result
        entry.state = ResidualLeaseState.EVICTED
        entries.remove(entry)
        reservations.remove(entry.reservation)
        entry.reservation.terminalizeInternal()
        true
    }

    /** 만료된 entry를 slot을 반환하지 않은 채 unknown으로 표시합니다. */
    fun expireDue(nowNanos: Long = monotonicNanos()): Int = lock.withLock {
        var changed = 0
        entries.forEach { entry ->
            if (entry.state == ResidualLeaseState.ACTIVE && nowNanos >= entry.retentionDeadlineNanos) {
                entry.state = ResidualLeaseState.QUARANTINED_UNKNOWN
                expiredUnknown++
                changed++
            }
        }
        changed
    }

    /** Evicts a quarantined entry only after fencing/TTL proof is supplied. */
    fun confirmProof(entry: ResidualEntry): Boolean = lock.withLock {
        if (entry.state != ResidualLeaseState.QUARANTINED_UNKNOWN || entry !in entries) return@withLock false
        entry.state = ResidualLeaseState.EVICTED
        entry.terminalResult = LeaseCleanupResult.NOT_HELD
        entries.remove(entry)
        reservations.remove(entry.reservation)
        entry.reservation.terminalizeInternal()
        true
    }

    internal fun releaseReservation(reservation: ResidualReservation) = lock.withLock {
        if (!reservations.containsKey(reservation)) return@withLock
        val entry = reservations.remove(reservation)
        if (entry != null) {
            entries.remove(entry)
            entry.state = ResidualLeaseState.EVICTED
        }
    }

    class ResidualReservation internal constructor(
        internal val owner: ResidualLeaseRegistry,
    ) : LeaseCleanupReservation {
        private val terminal = AtomicBoolean(false)
        private val terminalizer = AtomicReference<(() -> Unit)?>(null)

        override val isTerminal: Boolean get() = terminal.get()

        override fun terminalize() {
            if (terminal.compareAndSet(false, true)) {
                owner.releaseReservation(this)
                runTerminalizer()
            }
        }

        internal fun terminalizeInternal() {
            if (terminal.compareAndSet(false, true)) runTerminalizer()
        }

        internal fun registerTerminalizer(callback: (() -> Unit)?) {
            if (callback == null) return
            if (terminal.get()) {
                callback()
                return
            }
            terminalizer.set(callback)
            if (terminal.get()) runTerminalizer()
        }

        private fun runTerminalizer() {
            terminalizer.getAndSet(null)?.invoke()
        }
    }

    class ResidualEntry internal constructor(
        internal val reservation: ResidualReservation,
        val acquiredAtNanos: Long,
        val transferAtNanos: Long,
        val retentionDeadlineNanos: Long,
        val originContextGeneration: Long,
        val fencingProofAvailable: Boolean,
        val ttlProofAvailable: Boolean,
    ) {
        @Volatile
        var state: ResidualLeaseState = ResidualLeaseState.ACTIVE
            internal set

        @Volatile
        var terminalResult: LeaseCleanupResult? = null
            internal set
    }

    private fun safePlus(first: Long, second: Long): Long =
        if (second > 0 && first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second

    private fun retentionDeadline(acquiredAtNanos: Long, transferAtNanos: Long): Long {
        val retentionNanos = retention.inWholeNanoseconds.coerceAtLeast(0L)
        val lifetimeNanos = if (maxLeaseLifetime.isInfinite()) {
            Long.MAX_VALUE
        } else {
            maxLeaseLifetime.inWholeNanoseconds.coerceAtLeast(0L)
        }
        val acquiredDeadline = if (lifetimeNanos == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            safePlus(acquiredAtNanos, safePlus(lifetimeNanos, retentionNanos))
        }
        val transferDeadline = safePlus(transferAtNanos, retentionNanos)
        return minOf(acquiredDeadline, transferDeadline)
    }

}
