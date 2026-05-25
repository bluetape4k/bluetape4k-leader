package io.bluetape4k.leader.consul

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.consul.internal.ConsulLeaseHandle
import io.bluetape4k.leader.consul.internal.ConsulLockClient
import io.bluetape4k.leader.consul.internal.ConsulOwnerPayload
import io.bluetape4k.leader.consul.internal.ConsulSessionId
import io.bluetape4k.leader.consul.internal.ConsulSessionTtl
import io.bluetape4k.leader.consul.internal.ConsulSuspendLockExtendDelegate
import io.bluetape4k.leader.consul.internal.JavaHttpConsulLockClient
import io.bluetape4k.leader.consul.internal.getWithinRequestTimeout
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coroutine-native single-leader election backed by Consul Sessions and KV acquire/release.
 *
 * ## Behavior / Contract
 * - Normal contention returns `null`; action exceptions are propagated.
 * - Cancellation is rethrown after best-effort release and session destroy in [NonCancellable] cleanup.
 * - Consul Session TTL is derived from [ConsulLeaderElectionOptions.leaderOptions].
 * - The supplied [endpoint] is caller-owned configuration. This elector owns only its internal HTTP boundary.
 *
 * ```kotlin
 * val elector = ConsulSuspendLeaderElector(ConsulEndpoint("http://localhost:8500"))
 * val result = elector.runIfLeader("partition-leader") {
 *     processPartition()
 * }
 * ```
 */
class ConsulSuspendLeaderElector private constructor(
    private val lockClient: ConsulLockClient,
    val options: ConsulLeaderElectionOptions,
) : SuspendLeaderElector {

    constructor(
        endpoint: ConsulEndpoint,
        options: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
    ) : this(JavaHttpConsulLockClient(endpoint, options.keyPrefix), options)

    companion object : KLoggingChannel() {
        internal const val CONSUL_SUSPEND_FACTORY_BEAN_NAME = "consul-suspend-leader-elector"

        internal fun create(
            lockClient: ConsulLockClient,
            options: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
        ): ConsulSuspendLeaderElector =
            ConsulSuspendLeaderElector(lockClient, options)
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        runWithLock(lockName, null, action)

    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        runWithLock(slot.lockName, slot.leaderId, action)

    override suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runIfLeader(slot) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    /**
     * Returns a best-effort Consul KV state snapshot.
     *
     * This method follows the synchronous [io.bluetape4k.leader.LeaderElectionState] contract and blocks
     * up to the endpoint request timeout while reading Consul. Avoid calling it on a latency-sensitive
     * coroutine dispatcher.
     */
    override fun state(lockName: String): LeaderState {
        val key = lockClient.singleLockKey(lockName)
        val entry = lockClient.read(key).getWithinRequestTimeout(lockClient) ?: return LeaderState.empty(lockName)
        val sessionId = entry.sessionId ?: return LeaderState.empty(lockName)
        val lease = entry.value?.let { ConsulOwnerPayload.fromJson(it)?.toLeaderLease() }
        if (lease == null) {
            log.warn {
                "Consul suspend leader state ignored because owner payload is missing or invalid. " +
                    "lockName=$lockName, sessionId=${sessionId.value}"
            }
            return LeaderState.empty(lockName)
        }
        return LeaderState.occupied(lockName, lease)
    }

    private suspend fun <T> runWithLock(
        lockName: String,
        auditLeaderId: String?,
        action: suspend () -> T,
    ): T? {
        currentCoroutineContext().ensureActive()
        val handle = acquire(lockName, auditLeaderId) ?: return null
        val delegate: SuspendExtendDelegate = ConsulSuspendLockExtendDelegate(lockClient, handle)
        val lockHandle = LeaderLockHandle.real(
            identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = CONSUL_SUSPEND_FACTORY_BEAN_NAME,
            ),
            token = handle.ownerToken,
            acquiredAtNanos = handle.acquiredAtNanos,
            extendDelegate = delegate,
            auditLeaderId = handle.auditLeaderId,
        )
        val watchdog = LeaderLeaseAutoExtender.start(
            options.leaderOptions.autoExtend,
            options.leaderOptions.leaseTime,
            delegate,
            ConsulLeaderElector.ERROR_CLASSIFIER,
        )

        try {
            return withContext(AopScopeAccess.createLockHandleElement(lockHandle)) {
                action()
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    delayBeforeRelease(handle)
                } finally {
                    watchdog.close()
                    release(handle)
                }
            }
        }
    }

    private suspend fun acquire(lockName: String, auditLeaderId: String?): ConsulLeaseHandle? {
        val key = lockClient.singleLockKey(lockName)
        val electedAt = Instant.now()
        val leaseUntil = electedAt.plusMillis(options.leaderOptions.leaseTime.inWholeMilliseconds)
        val ownerToken = Base58.randomString(12)
        val payload = ConsulOwnerPayload(
            ownerToken = ownerToken,
            auditLeaderId = auditLeaderId ?: options.leaderOptions.nodeId,
            nodeId = options.leaderOptions.nodeId,
            electedAt = electedAt,
            leaseUntil = leaseUntil,
        )
        val sessionId = try {
            lockClient.createSession(
                name = "${options.sessionNamePrefix}-${options.leaderOptions.nodeId}",
                ttl = options.leaderOptions.leaseTime,
                lockDelay = options.lockDelay,
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LeaderElectionException("Failed to create Consul suspend session. lockName=$lockName", e)
        }

        try {
            if (!acquireWithinWaitTime(key, sessionId, payload.toJson())) {
                destroySession(sessionId)
                log.debug { "Consul suspend leader lock acquisition skipped by contention. lockName=$lockName" }
                return null
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                destroySession(sessionId)
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                destroySession(sessionId)
            }
            throw LeaderElectionException("Failed to acquire Consul suspend leader lock. lockName=$lockName", e)
        }

        return ConsulLeaseHandle(
            lockName = lockName,
            key = key,
            sessionId = sessionId,
            ownerToken = ownerToken,
            auditLeaderId = payload.auditLeaderId,
            nodeId = payload.nodeId,
            electedAt = electedAt,
            leaseUntil = leaseUntil,
        )
    }

    private suspend fun acquireWithinWaitTime(
        key: String,
        sessionId: ConsulSessionId,
        ownerPayload: String,
    ): Boolean {
        val timeoutNanos = options.leaderOptions.waitTime.inWholeNanoseconds
        val deadline = System.nanoTime() + timeoutNanos
        val renewDelayNanos = ConsulSessionTtl.renewDelay(options.leaderOptions.leaseTime).inWholeNanoseconds
        var lastRenewNanos = System.nanoTime()

        do {
            currentCoroutineContext().ensureActive()
            // If cancellation races after Consul accepts the acquire but before await resumes,
            // the acquired session remains the recovery boundary; normal cleanup destroys it
            // after await returns, and otherwise Consul TTL releases the key.
            if (lockClient.acquire(key, sessionId, ownerPayload).await()) {
                return true
            }
            if (timeoutNanos == 0L || System.nanoTime() >= deadline) {
                return false
            }
            val now = System.nanoTime()
            if (now - lastRenewNanos >= renewDelayNanos) {
                lockClient.renewSession(sessionId).await()
                lastRenewNanos = now
            }
            val delayMillis = minOf(50.milliseconds.inWholeMilliseconds, remainingMillis(deadline)).coerceAtLeast(1)
            delay(delayMillis.milliseconds)
        } while (true)
    }

    private suspend fun delayBeforeRelease(handle: ConsulLeaseHandle) {
        val remaining = remainingMinLeaseTime(handle.acquiredAtNanos, options.leaderOptions.minLeaseTime)
        if (remaining.isPositive()) {
            delay(remaining)
        }
    }

    private suspend fun release(handle: ConsulLeaseHandle) {
        if (!handle.markReleased()) {
            return
        }

        try {
            lockClient.release(handle.key, handle.sessionId).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Failed to release Consul suspend leader lock. lockName=${handle.lockName}" }
        }
        destroySession(handle.sessionId)
    }

    private suspend fun destroySession(sessionId: ConsulSessionId) {
        try {
            lockClient.destroySession(sessionId).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Failed to destroy Consul suspend session. sessionId=${sessionId.value}" }
        }
    }
}

private fun remainingMillis(deadlineNanos: Long): Long =
    ((deadlineNanos - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L).coerceAtLeast(1L)

/**
 * Runs [action] only when this Consul endpoint acquires the leader lock in a coroutine.
 *
 * ## Behavior / Contract
 * - Returns `null` on normal contention.
 * - Propagates exceptions and coroutine cancellation from [action].
 * - The endpoint is caller-owned; this helper creates a short-lived elector for one call.
 */
suspend fun <T> ConsulEndpoint.suspendRunIfLeader(
    lockName: String,
    options: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? = ConsulSuspendLeaderElector(this, options).runIfLeader(lockName, action)
