package io.bluetape4k.leader.consul

import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.consul.internal.ConsulLeaseHandle
import io.bluetape4k.leader.consul.internal.ConsulLockClient
import io.bluetape4k.leader.consul.internal.ConsulLockExtendDelegate
import io.bluetape4k.leader.consul.internal.ConsulOwnerPayload
import io.bluetape4k.leader.consul.internal.ConsulBackendErrorClassifier
import io.bluetape4k.leader.consul.internal.ConsulSessionId
import io.bluetape4k.leader.consul.internal.ConsulSessionTtl
import io.bluetape4k.leader.consul.internal.JavaHttpConsulLockClient
import io.bluetape4k.leader.consul.internal.getWithinRequestTimeout
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.milliseconds

/**
 * Blocking and async single-leader election backed by Consul Sessions and KV acquire/release.
 *
 * ## Behavior / Contract
 * - Normal contention returns `null`; action exceptions are propagated.
 * - The supplied [endpoint] is caller-owned configuration. This elector owns only its internal HTTP boundary.
 * - Consul ACL token and datacenter are read from [ConsulEndpoint].
 * - Release/destroy cleanup is best-effort and owner/session scoped.
 */
class ConsulLeaderElector private constructor(
    private val lockClient: ConsulLockClient,
    val options: ConsulLeaderElectionOptions,
) : LeaderElector {

    constructor(
        endpoint: ConsulEndpoint,
        options: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
    ) : this(JavaHttpConsulLockClient(endpoint, options.keyPrefix), options)

    companion object : KLogging() {
        internal const val CONSUL_FACTORY_BEAN_NAME = "consul-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ConsulBackendErrorClassifier)

        internal fun create(
            lockClient: ConsulLockClient,
            options: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
        ): ConsulLeaderElector =
            ConsulLeaderElector(lockClient, options)
    }

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        runWithLock(lockName, null, action)

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        runWithLock(slot.lockName, slot.leaderId, action)

    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runIfLeader(slot) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        runAsyncWithLock(lockName, null, executor, action)

    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        runAsyncWithLock(slot.lockName, slot.leaderId, executor, action)

    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        var elected = false
        return runAsyncIfLeader(slot, executor) {
            elected = true
            action()
        }.handle { value, failure ->
            val cause = failure.unwrapCompletionException()
            when {
                cause is CancellationException -> throw cause
                cause != null && elected -> LeaderRunResult.ActionFailed(cause)
                cause != null -> throw CompletionException(cause)
                elected -> LeaderRunResult.Elected(value, leaderId = slot.leaderId)
                else -> LeaderRunResult.Skipped
            }
        }
    }

    override fun state(lockName: String): LeaderState {
        val key = lockClient.singleLockKey(lockName)
        val entry = lockClient.read(key).getWithinRequestTimeout(lockClient) ?: return LeaderState.empty(lockName)
        val sessionId = entry.sessionId ?: return LeaderState.empty(lockName)
        val lease = entry.value?.let { ConsulOwnerPayload.fromJson(it)?.toLeaderLease() }
        if (lease == null) {
            log.warn {
                "Consul leader state ignored because owner payload is missing or invalid. " +
                    "lockName=$lockName, sessionId=${sessionId.value}"
            }
            return LeaderState.empty(lockName)
        }
        return LeaderState.occupied(lockName, lease)
    }

    private fun <T> runWithLock(lockName: String, auditLeaderId: String?, action: () -> T): T? {
        val handle = acquire(lockName, auditLeaderId) ?: return null
        val delegate = ConsulLockExtendDelegate(lockClient, handle)
        val lockHandle = LeaderLockHandle.real(
            identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = CONSUL_FACTORY_BEAN_NAME,
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
            ERROR_CLASSIFIER,
        )

        try {
            return AopScopeAccess.withPushedSync(lockHandle) { action() }
        } finally {
            watchdog.close()
            release(handle)
        }
    }

    private fun <T> runAsyncWithLock(
        lockName: String,
        auditLeaderId: String?,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        CompletableFuture.supplyAsync({ acquire(lockName, auditLeaderId) }, executor)
            .thenComposeAsync({ handle ->
                if (handle == null) {
                    CompletableFuture.completedFuture(null)
                } else {
                    runAcquiredAsync(handle, executor, action)
                }
            }, executor)

    private fun <T> runAcquiredAsync(
        handle: ConsulLeaseHandle,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val delegate = ConsulLockExtendDelegate(lockClient, handle)
        val watchdog = LeaderLeaseAutoExtender.start(
            options.leaderOptions.autoExtend,
            options.leaderOptions.leaseTime,
            delegate,
            ERROR_CLASSIFIER,
        )
        val actionFuture = try {
            action()
        } catch (e: Exception) {
            watchdog.close()
            release(handle)
            return CompletableFuture.failedFuture(e)
        }

        return actionFuture.handleAsync({ value, failure ->
            watchdog.close()
            release(handle)
            val cause = failure.unwrapCompletionException()
            if (cause != null) {
                throw CompletionException(cause)
            }
            value
        }, executor)
    }

    private fun acquire(lockName: String, auditLeaderId: String?): ConsulLeaseHandle? {
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
            ).getWithinRequestTimeout(lockClient)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: Exception) {
            throw LeaderElectionException("Failed to create Consul session. lockName=$lockName", e)
        }

        val acquired = try {
            acquireWithinWaitTime(key, sessionId, payload.toJson())
        } catch (e: Exception) {
            destroySession(sessionId)
            throw LeaderElectionException("Failed to acquire Consul leader lock. lockName=$lockName", e)
        }

        if (!acquired) {
            destroySession(sessionId)
            log.debug { "Consul leader lock acquisition skipped by contention. lockName=$lockName" }
            return null
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

    private fun acquireWithinWaitTime(
        key: String,
        sessionId: ConsulSessionId,
        ownerPayload: String,
    ): Boolean {
        val timeoutNanos = options.leaderOptions.waitTime.inWholeNanoseconds
        val deadline = System.nanoTime() + timeoutNanos
        val renewDelayNanos = ConsulSessionTtl.renewDelay(options.leaderOptions.leaseTime).inWholeNanoseconds
        var lastRenewNanos = System.nanoTime()

        do {
            try {
                if (lockClient.acquire(key, sessionId, ownerPayload).getWithinRequestTimeout(lockClient)) {
                    return true
                }
                if (timeoutNanos == 0L || System.nanoTime() >= deadline) {
                    return false
                }
                val now = System.nanoTime()
                if (now - lastRenewNanos >= renewDelayNanos) {
                    lockClient.renewSession(sessionId).getWithinRequestTimeout(lockClient)
                    lastRenewNanos = now
                }
                Thread.sleep(minOf(50.milliseconds.inWholeMilliseconds, remainingMillis(deadline)).coerceAtLeast(1))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        } while (true)
    }

    private fun release(handle: ConsulLeaseHandle) {
        if (!handle.markReleased()) {
            return
        }

        val remaining = remainingMinLeaseTime(handle.acquiredAtNanos, options.leaderOptions.minLeaseTime)
        if (remaining.isPositive()) {
            try {
                Thread.sleep(remaining.inWholeMilliseconds)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        runCatching { lockClient.release(handle.key, handle.sessionId).getWithinRequestTimeout(lockClient) }
            .onFailure { e ->
                log.warn(e) { "Failed to release Consul leader lock. lockName=${handle.lockName}" }
            }
        destroySession(handle.sessionId)
    }

    private fun destroySession(sessionId: ConsulSessionId) {
        runCatching { lockClient.destroySession(sessionId).getWithinRequestTimeout(lockClient) }
            .onFailure { e ->
                log.warn(e) { "Failed to destroy Consul session. sessionId=${sessionId.value}" }
            }
    }
}

private fun Throwable?.unwrapCompletionException(): Throwable? =
    if (this is CompletionException && cause != null) cause else this

private fun remainingMillis(deadlineNanos: Long): Long =
    ((deadlineNanos - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L).coerceAtLeast(1L)

/**
 * Runs [action] only when this Consul endpoint acquires the leader lock.
 */
fun <T> ConsulEndpoint.runIfLeader(
    lockName: String,
    options: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
    action: () -> T,
): T? = ConsulLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * Runs [action] asynchronously only when this Consul endpoint acquires the leader lock.
 */
fun <T> ConsulEndpoint.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ConsulLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)
