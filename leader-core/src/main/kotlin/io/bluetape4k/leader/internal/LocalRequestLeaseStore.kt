package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseHandle
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.parkRemainingMinLeaseTime
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import io.bluetape4k.support.requireGt

/**
 * thread ownership에 의존하지 않는 local request lease 저장소입니다.
 *
 * 기존 action path의 [java.util.concurrent.locks.ReentrantLock]과 분리하여 completion
 * thread가 달라도 generation을 비교해 release할 수 있도록 합니다.
 */
internal class LocalRequestLeaseStore {

    private val monitors = ConcurrentHashMap<String, Monitor>()
    private val records = ConcurrentHashMap<String, Record>()

    fun tryAcquire(slot: LeaderSlot, waitTime: Duration, options: LeaderElectionOptions): LocalRequestLeaseHandle? {
        val record = tryAcquireRecord(slot, waitTime, options) ?: return null
        return LocalRequestLeaseHandle(this, record, options)
    }

    @Suppress("ReturnCount")
    private fun tryAcquireRecord(slot: LeaderSlot, waitTime: Duration, options: LeaderElectionOptions): Record? {
        val monitor = monitors.computeIfAbsent(slot.lockName) { Monitor() }
        val deadline = System.nanoTime() + waitTime.inWholeNanoseconds.coerceAtLeast(0L)

        if (!monitor.lock.lockInterruptiblyOrNull()) return null
        try {
            while (true) {
                val current = records[slot.lockName]
                if (current == null || current.isExpired()) {
                    current?.let {
                        it.released.set(true)
                        records.remove(slot.lockName, it)
                    }
                    val record = Record(slot, options.leaseTime)
                    records[slot.lockName] = record
                    return record
                }

                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) return null
                try {
                    monitor.changed.awaitNanos(remainingNanos)
                } catch (ex: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        } finally {
            monitor.lock.unlock()
        }
    }

    suspend fun tryAcquireSuspend(
        slot: LeaderSlot,
        waitTime: Duration,
        options: LeaderElectionOptions,
    ): LocalSuspendRequestLeaseHandle? = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        tryAcquireRecord(slot, waitTime, options)?.let {
            LocalSuspendRequestLeaseHandle(this@LocalRequestLeaseStore, it, options)
        }
    }

    fun release(record: Record): Boolean {
        val monitor = monitors.computeIfAbsent(record.slot.lockName) { Monitor() }
        monitor.lock.lock()
        try {
            val removed = records.remove(record.slot.lockName, record)
            if (removed) {
                record.released.set(true)
                monitor.changed.signalAll()
            }
            return removed
        } finally {
            monitor.lock.unlock()
        }
    }

    fun extend(record: Record, duration: Duration): ExtendOutcome {
        duration.requireGt(Duration.ZERO, "lockAtMostFor")
        val monitor = monitors.computeIfAbsent(record.slot.lockName) { Monitor() }
        monitor.lock.lock()
        try {
            val current = records[record.slot.lockName]
            if (current !== record || record.released.get() || record.isExpired()) {
                if (current === record) {
                    records.remove(record.slot.lockName, record)
                    record.released.set(true)
                    monitor.changed.signalAll()
                }
                return ExtendOutcome.NotHeld
            }
            record.expiresAtNanos = System.nanoTime() + duration.inWholeNanoseconds
            record.expiresAt = Instant.now().plusMillis(duration.inWholeMilliseconds)
            return ExtendOutcome.Extended(record.expiresAt)
        } finally {
            monitor.lock.unlock()
        }
    }

    fun ownershipStatus(record: Record): LeaseOwnershipStatus {
        val monitor = monitors.computeIfAbsent(record.slot.lockName) { Monitor() }
        monitor.lock.lock()
        try {
            val current = records[record.slot.lockName]
            if (current !== record || record.released.get() || record.isExpired()) {
                return LeaseOwnershipStatus.NOT_HELD
            }
            return LeaseOwnershipStatus.HELD
        } finally {
            monitor.lock.unlock()
        }
    }

    private class Monitor {
        val lock = ReentrantLock()
        val changed = lock.newCondition()
    }

    internal class Record(
        val slot: LeaderSlot,
        leaseTime: Duration,
    ) {
        val acquiredAt: Instant = Instant.now()
        val acquiredAtNanos: Long = System.nanoTime()
        val generation: Long = GENERATION.incrementAndGet()
        val released = AtomicBoolean(false)
        @Volatile
        var expiresAtNanos: Long = acquiredAtNanos + leaseTime.inWholeNanoseconds
        @Volatile
        var expiresAt: Instant = acquiredAt.plusMillis(leaseTime.inWholeMilliseconds)

        fun isExpired(): Boolean = System.nanoTime() >= expiresAtNanos
    }

    companion object {
        private val GENERATION = java.util.concurrent.atomic.AtomicLong()
    }
}

private fun ReentrantLock.lockInterruptiblyOrNull(): Boolean =
    try {
        lockInterruptibly()
        true
    } catch (ex: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

/** local request handle that fences by record identity/generation. */
internal class LocalRequestLeaseHandle(
    private val store: LocalRequestLeaseStore,
    private val record: LocalRequestLeaseStore.Record,
    private val options: LeaderElectionOptions,
) : LeaderLeaseHandle {

    private val closed = AtomicBoolean(false)
    @Volatile
    private var watchdog: AutoCloseable? = null

    override val lockName: String get() = record.slot.lockName
    override val auditLeaderId: String get() = record.slot.leaderId
    override val acquiredAt: Instant get() = record.acquiredAt

    init {
        val delegate = object : ExtendDelegate {
            override val lastExtendDeadline = java.util.concurrent.atomic.AtomicReference(Instant.EPOCH)
            override fun extend(lockAtMostFor: Duration): ExtendOutcome = store.extend(record, lockAtMostFor)
            override fun isHeld(): Boolean = store.ownershipStatus(record) == LeaseOwnershipStatus.HELD
        }
        watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate)
    }

    override fun extend(lockAtMostFor: Duration): ExtendOutcome {
        if (closed.get()) return ExtendOutcome.NotHeld
        return store.extend(record, lockAtMostFor)
    }

    override fun ownershipStatus(): LeaseOwnershipStatus = store.ownershipStatus(record)

    override fun isStillHeld(): Boolean = ownershipStatus() == LeaseOwnershipStatus.HELD

    override fun release() {
        if (!closed.compareAndSet(false, true)) return
        watchdog?.close()
        watchdog = null
        parkRemainingMinLeaseTime(record.acquiredAtNanos, options.minLeaseTime)
        store.release(record)
    }

}

/** suspend local request handle with the same record-identity fencing. */
internal class LocalSuspendRequestLeaseHandle(
    private val store: LocalRequestLeaseStore,
    private val record: LocalRequestLeaseStore.Record,
    private val options: LeaderElectionOptions,
) : SuspendLeaderLeaseHandle {

    private val closed = AtomicBoolean(false)
    @Volatile
    private var watchdog: AutoCloseable? = null

    override val lockName: String get() = record.slot.lockName
    override val auditLeaderId: String get() = record.slot.leaderId
    override val acquiredAt: Instant get() = record.acquiredAt

    init {
        val delegate = object : ExtendDelegate {
            override val lastExtendDeadline = java.util.concurrent.atomic.AtomicReference(Instant.EPOCH)
            override fun extend(lockAtMostFor: Duration): ExtendOutcome = store.extend(record, lockAtMostFor)
            override fun isHeld(): Boolean = store.ownershipStatus(record) == LeaseOwnershipStatus.HELD
        }
        watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate)
    }

    override suspend fun extend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        if (closed.get()) ExtendOutcome.NotHeld else store.extend(record, lockAtMostFor)
    }

    override suspend fun ownershipStatus(): LeaseOwnershipStatus =
        withContext(Dispatchers.IO) { store.ownershipStatus(record) }

    override suspend fun isStillHeld(): Boolean = ownershipStatus() == LeaseOwnershipStatus.HELD

    override suspend fun release() = withContext(NonCancellable + Dispatchers.IO) {
        if (closed.compareAndSet(false, true)) {
            watchdog?.close()
            watchdog = null
            val remaining = io.bluetape4k.leader.remainingMinLeaseTime(record.acquiredAtNanos, options.minLeaseTime)
            if (remaining > Duration.ZERO) delay(remaining)
            store.release(record)
        }
    }
}
