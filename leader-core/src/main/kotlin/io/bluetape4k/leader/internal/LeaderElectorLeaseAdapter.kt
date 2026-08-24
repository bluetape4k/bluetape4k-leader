package io.bluetape4k.leader.internal

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAcquirer
import io.bluetape4k.leader.LeaderLeaseHandle
import io.bluetape4k.leader.LeaderLeaseDefaults
import io.bluetape4k.leader.LeaderLeaseWatchdogAdmission
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.support.requireNotBlank
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * 기존 blocking elector를 새 backend lock 구현 없이 request lease로 연결합니다.
 * backend token과 watchdog은 elector가 소유하고, adapter는 request handle이 닫힐
 * 때까지 elected action을 유지합니다. backend token이나 address는 노출하지 않습니다.
 */
class LeaderElectorLeaseAdapter(
    private val electorProvider: () -> LeaderElector,
    override val configuredOptions: LeaderElectionOptions,
) : LeaderLeaseAcquirer {

    override fun tryAcquire(lockName: String): LeaderLeaseHandle? {
        lockName.requireNotBlank("lockName")
        return tryAcquire(LeaderSlot(lockName, configuredOptions.nodeId))
    }

    @Suppress("TooGenericExceptionCaught")
    override fun tryAcquire(slot: LeaderSlot): LeaderLeaseHandle? {
        slot.lockName.requireNotBlank("lockName")
        val session = SyncSession(slot, configuredOptions.leaseTime)
        val elected = CompletableFuture<SyncSession?>()
        val admission = LeaderLeaseWatchdogAdmission.current()
        val owner = Thread.startVirtualThread {
            LeaderLeaseWatchdogAdmission.withOptionalProvider(admission) {
                try {
                    electorProvider().runIfLeader(slot) {
                        val captured = AopScopeAccess.peekSyncMatching(slot.lockName)
                        session.install(captured)
                        if (captured !is LeaderLockHandle.Real || session.cancelled.get()) {
                            session.cancelled.set(true)
                            session.requestRelease()
                        } else {
                            elected.complete(session)
                            session.awaitRelease()
                        }
                        Unit
                    }
                    if (!elected.isDone) elected.complete(null)
                    session.completed.complete(Unit)
                } catch (failure: Throwable) {
                    if (!elected.isDone) elected.completeExceptionally(failure)
                    session.completed.completeExceptionally(failure)
                }
            }
        }

        return try {
            elected.get(acquireTimeoutNanos(), TimeUnit.NANOSECONDS)?.let {
                AdapterLeaseHandle(session, configuredOptions.leaseTime)
            }
        } catch (_: TimeoutException) {
            session.cancelled.set(true)
            owner.interrupt()
            null
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            session.cancelled.set(true)
            owner.interrupt()
            null
        }
    }

    private fun acquireTimeoutNanos(): Long =
        configuredOptions.waitTime.inWholeNanoseconds.coerceAtLeast(1L)

    private class SyncSession(
        val slot: LeaderSlot,
        private val maxLeaseTime: Duration,
    ) {
        private sealed interface Command {
            data class Extend(val duration: Duration, val result: CompletableFuture<ExtendOutcome>) : Command
            data class Held(val result: CompletableFuture<LeaseOwnershipStatus>) : Command
            data object Release : Command
        }

        private val commands = ArrayBlockingQueue<Command>(32)
        val cancelled = AtomicBoolean(false)
        val completed = CompletableFuture<Unit>()
        private val released = AtomicBoolean(false)
        private val terminalStatus = AtomicReference<LeaseOwnershipStatus?>(null)
        @Volatile
        private var raw: io.bluetape4k.leader.LeaderLockHandle? = null

        fun install(handle: io.bluetape4k.leader.LeaderLockHandle?) {
            raw = handle
        }

        fun awaitRelease() {
            while (!released.get()) {
                val command = try {
                    commands.take()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    released.set(true)
                    return
                }
                when (command) {
                    is Command.Extend -> command.result.complete(extendRaw(command.duration))
                    is Command.Held -> command.result.complete(
                        statusRaw()
                    )
                    Command.Release -> released.set(true)
                }
            }
        }

        fun requestRelease() {
            released.set(true)
        }

        private fun extendRaw(duration: Duration): ExtendOutcome =
            when (val handle = raw) {
                is io.bluetape4k.leader.LeaderLockHandle.Real -> handle.extend(duration)
                null -> ExtendOutcome.NotHeld
                else -> ExtendOutcome.Rejected
            }

        private fun statusRaw(): LeaseOwnershipStatus =
            when (val handle = raw) {
                is io.bluetape4k.leader.LeaderLockHandle.Real ->
                    if (handle.isStillHeld()) LeaseOwnershipStatus.HELD else LeaseOwnershipStatus.NOT_HELD
                null -> LeaseOwnershipStatus.UNKNOWN
                else -> LeaseOwnershipStatus.UNKNOWN
            }

        @Suppress("ReturnCount")
        fun extend(duration: Duration): ExtendOutcome {
            if (released.get() || cancelled.get()) return ExtendOutcome.NotHeld
            val result = CompletableFuture<ExtendOutcome>()
            if (!commands.offer(Command.Extend(duration, result))) return ExtendOutcome.Rejected
            return try {
                result.get(duration.inWholeNanoseconds.coerceAtLeast(1L), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                ExtendOutcome.Rejected
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                ExtendOutcome.Rejected
            } catch (failure: ExecutionException) {
                ExtendOutcome.BackendError(
                    (failure.cause as? Exception) ?: RuntimeException(failure.cause ?: failure),
                )
            }
        }

        @Suppress("ReturnCount")
        fun status(): LeaseOwnershipStatus {
            val terminal = terminalStatus.get()
            if (terminal != null) return terminal
            if (released.get() || cancelled.get()) return LeaseOwnershipStatus.UNKNOWN
            val result = CompletableFuture<LeaseOwnershipStatus>()
            if (!commands.offer(Command.Held(result))) return LeaseOwnershipStatus.UNKNOWN
            return try {
                result.get(1, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                LeaseOwnershipStatus.UNKNOWN
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                LeaseOwnershipStatus.UNKNOWN
            } catch (_: ExecutionException) {
                LeaseOwnershipStatus.UNKNOWN
            }
        }

        fun release() {
            if (!released.compareAndSet(false, true)) return
            commands.offer(Command.Release)
            try {
                completed.get(releaseTimeoutNanos(), TimeUnit.NANOSECONDS)
                terminalStatus.compareAndSet(null, LeaseOwnershipStatus.NOT_HELD)
            } catch (failure: TimeoutException) {
                terminalStatus.set(LeaseOwnershipStatus.UNKNOWN)
                throw failure
            } catch (failure: ExecutionException) {
                terminalStatus.set(LeaseOwnershipStatus.UNKNOWN)
                throw failure.cause ?: failure
            }
        }

        private fun releaseTimeoutNanos(): Long =
            minOf(LeaderLeaseDefaults.PUBLIC_RELEASE_TIMEOUT, maxLeaseTime).inWholeNanoseconds.coerceAtLeast(1L)
    }

    private class AdapterLeaseHandle(
        private val session: SyncSession,
        private val maxLeaseTime: Duration,
    ) : LeaderLeaseHandle {
        override val lockName: String get() = session.slot.lockName
        override val auditLeaderId: String get() = session.slot.leaderId
        override val acquiredAt: Instant = Instant.now()

        override fun extend(lockAtMostFor: Duration): ExtendOutcome =
            session.extend(lockAtMostFor.coerceAtMost(maxLeaseTime))

        override fun ownershipStatus(): LeaseOwnershipStatus = session.status()

        override fun isStillHeld(): Boolean = ownershipStatus() == LeaseOwnershipStatus.HELD

        override fun release() = session.release()
    }
}
