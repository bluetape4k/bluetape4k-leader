package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.LeaderLeaseWatchdogAdmission
import io.bluetape4k.leader.LeaderLeaseDefaults
import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseHandle
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeoutException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 기존 suspend elector를 capability-preserving request lease로 연결합니다. backend의
 * suspend action 경로가 acquire/watchdog/conditional release를 계속 소유하고 adapter는
 * request lifetime만 유지합니다.
 */
class SuspendLeaderElectorLeaseAdapter(
    private val electorProvider: () -> SuspendLeaderElector,
    override val configuredOptions: LeaderElectionOptions,
) : SuspendLeaderLeaseAcquirer {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun tryAcquire(lockName: String): SuspendLeaderLeaseHandle? {
        lockName.requireNotBlank("lockName")
        return tryAcquire(LeaderSlot(lockName, configuredOptions.nodeId))
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun tryAcquire(slot: LeaderSlot): SuspendLeaderLeaseHandle? {
        slot.lockName.requireNotBlank("lockName")
        coroutineContext.ensureActive()
        val session = SuspendSession(slot, configuredOptions.leaseTime)
        val published = CompletableDeferred<SuspendSession?>()
        val admission = LeaderLeaseWatchdogAdmission.current()
        val admissionContext = admission?.let(LeaderLeaseWatchdogAdmission::asContextElement)
        val job = scope.launch(admissionContext ?: EmptyCoroutineContext) {
            try {
                electorProvider().runIfLeader(slot) {
                    val captured = captureSuspendHandle()
                    session.install(captured)
                    if (captured !is LeaderLockHandle.Real || session.cancelled.get()) {
                        session.cancelled.set(true)
                        session.requestRelease()
                    } else {
                        published.complete(session)
                        session.awaitRelease()
                    }
                    Unit
                }
                if (!published.isCompleted) published.complete(null)
                session.completed.complete(Unit)
            } catch (failure: Throwable) {
                if (!published.isCompleted) published.completeExceptionally(failure)
                session.completed.completeExceptionally(failure)
            }
        }

        return try {
            val value = withTimeoutOrNull(configuredOptions.waitTime) { published.await() }
            if (value != null) {
                SuspendAdapterLeaseHandle(session, configuredOptions.leaseTime)
            } else {
                cancelAttempt(session, job)
                null
            }
        } catch (cancelled: CancellationException) {
            cancelAttempt(session, job)
            throw cancelled
        }
    }

    private fun cancelAttempt(session: SuspendSession, job: kotlinx.coroutines.Job) {
        session.cancelled.set(true)
        session.requestRelease()
        job.cancel()
    }

    private suspend fun captureSuspendHandle(): io.bluetape4k.leader.LeaderLockHandle? =
        coroutineContext[LockHandleElement]?.handle

    private class SuspendSession(
        val slot: LeaderSlot,
        private val maxLeaseTime: Duration,
    ) {
        private sealed interface Command {
            data class Extend(val duration: Duration, val result: CompletableDeferred<ExtendOutcome>) : Command
            data class Held(val result: CompletableDeferred<LeaseOwnershipStatus>) : Command
            data object Release : Command
        }

        private val commands = Channel<Command>(capacity = 32)
        val cancelled = AtomicBoolean(false)
        val completed = CompletableDeferred<Unit>()
        private val released = AtomicBoolean(false)
        private val terminalStatus = AtomicReference<LeaseOwnershipStatus?>(null)
        @Volatile
        private var raw: io.bluetape4k.leader.LeaderLockHandle? = null

        fun install(handle: io.bluetape4k.leader.LeaderLockHandle?) {
            raw = handle
        }

        suspend fun awaitRelease() {
            while (!released.get()) {
                when (val command = commands.receive()) {
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
        suspend fun extend(duration: Duration): ExtendOutcome {
            if (released.get() || cancelled.get()) return ExtendOutcome.NotHeld
            val result = CompletableDeferred<ExtendOutcome>()
            if (!commands.trySend(Command.Extend(duration, result)).isSuccess) return ExtendOutcome.Rejected
            return withTimeoutOrNull(duration) { result.await() } ?: ExtendOutcome.Rejected
        }

        @Suppress("ReturnCount")
        suspend fun status(): LeaseOwnershipStatus {
            val terminal = terminalStatus.get()
            if (terminal != null) return terminal
            if (released.get() || cancelled.get()) return LeaseOwnershipStatus.UNKNOWN
            val result = CompletableDeferred<LeaseOwnershipStatus>()
            if (!commands.trySend(Command.Held(result)).isSuccess) return LeaseOwnershipStatus.UNKNOWN
            return withTimeoutOrNull(1.seconds) { result.await() } ?: LeaseOwnershipStatus.UNKNOWN
        }

        suspend fun release() {
            if (!released.compareAndSet(false, true)) return
            commands.trySend(Command.Release)
            val timeout = minOf(LeaderLeaseDefaults.PUBLIC_RELEASE_TIMEOUT, maxLeaseTime)
            if (withTimeoutOrNull(timeout) { completed.await() } == null) {
                terminalStatus.set(LeaseOwnershipStatus.UNKNOWN)
                throw TimeoutException("suspend lease release timed out")
            }
            terminalStatus.compareAndSet(null, LeaseOwnershipStatus.NOT_HELD)
        }
    }

    private class SuspendAdapterLeaseHandle(
        private val session: SuspendSession,
        private val maxLeaseTime: Duration,
    ) : SuspendLeaderLeaseHandle {
        override val lockName: String get() = session.slot.lockName
        override val auditLeaderId: String get() = session.slot.leaderId
        override val acquiredAt: Instant = Instant.now()

        override suspend fun extend(lockAtMostFor: Duration): ExtendOutcome =
            session.extend(lockAtMostFor.coerceAtMost(maxLeaseTime))

        override suspend fun ownershipStatus(): LeaseOwnershipStatus = session.status()

        override suspend fun isStillHeld(): Boolean = ownershipStatus() == LeaseOwnershipStatus.HELD

        override suspend fun release() = session.release()
    }

    /** Stops the bridge scope when it is used outside a managed context. */
    fun close() {
        scope.cancel()
    }
}
