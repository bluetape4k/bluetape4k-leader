package io.bluetape4k.leader.consul

import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.consul.internal.ConsulLeaseHandle
import io.bluetape4k.leader.consul.internal.ConsulLockClient
import io.bluetape4k.leader.consul.internal.ConsulLockExtendDelegate
import io.bluetape4k.leader.consul.internal.ConsulOwnerPayload
import io.bluetape4k.leader.consul.internal.ConsulSessionId
import io.bluetape4k.leader.consul.internal.ConsulSessionTtl
import io.bluetape4k.leader.consul.internal.JavaHttpConsulLockClient
import io.bluetape4k.leader.consul.internal.getWithinRequestTimeout
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration.Companion.milliseconds

/**
 * Blocking and async Consul multi-leader election backed by fixed KV slot keys and Consul Sessions.
 *
 * ## Behavior / Contract
 * - At most [maxLeaders] actions run concurrently per `lockName`.
 * - Normal full-group contention returns `null`; action exceptions are propagated or reported by result APIs.
 * - Slots are stable KV keys: `keyPrefix/group/{encodedLockName}/slot-{index}`.
 * - Consul Session TTL is derived from [ConsulLeaderGroupElectionOptions.leaderGroupOptions].
 * - A crashed process releases its slot when Consul expires the session; until then, another contender skips or waits.
 * - State snapshots are best-effort: a slot with invalid owner payload is treated as unknown and is not counted.
 * - The supplied [endpoint] is caller-owned configuration. This elector owns only its internal HTTP boundary.
 */
class ConsulLeaderGroupElector private constructor(
    private val lockClient: ConsulLockClient,
    val options: ConsulLeaderGroupElectionOptions,
) : LeaderGroupElector {

    constructor(
        endpoint: ConsulEndpoint,
        options: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
    ) : this(JavaHttpConsulLockClient(endpoint, options.keyPrefix), options)

    companion object : KLogging() {
        internal const val CONSUL_GROUP_FACTORY_BEAN_NAME = "consul-leader-group-elector"

        internal fun create(
            lockClient: ConsulLockClient,
            options: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
        ): ConsulLeaderGroupElector =
            ConsulLeaderGroupElector(lockClient, options)
    }

    override val maxLeaders: Int = options.maxLeaders

    override fun activeCount(lockName: String): Int =
        currentLeaders(lockName).size

    override fun availableSlots(lockName: String): Int =
        maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState {
        val leaders = currentLeaders(lockName)
        return LeaderGroupState(lockName, maxLeaders, leaders.size, leaders)
    }

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        runWithSlot(lockName, auditLeaderId = null, action)

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        runWithSlot(slot.lockName, auditLeaderId = slot.leaderId, action)

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
        runAsyncWithSlot(lockName, auditLeaderId = null, executor, action)

    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        runAsyncWithSlot(slot.lockName, auditLeaderId = slot.leaderId, executor, action)

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

    private fun <T> runWithSlot(lockName: String, auditLeaderId: String?, action: () -> T): T? {
        val handle = acquire(lockName, auditLeaderId) ?: return null
        val delegate = ConsulLockExtendDelegate(lockClient, handle)
        val lockHandle = LeaderLockHandle.real(
            identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.GROUP,
                factoryBeanName = CONSUL_GROUP_FACTORY_BEAN_NAME,
                groupParams = LockIdentity.GroupParams(maxLeaders),
            ),
            token = handle.ownerToken,
            acquiredAtNanos = handle.acquiredAtNanos,
            slotId = handle.slotId,
            extendDelegate = delegate,
            auditLeaderId = handle.auditLeaderId,
        )
        val watchdog = LeaderLeaseAutoExtender.start(
            // Group auto-extension is disabled; explicit LockExtender renews the Consul session.
            enabled = false,
            leaseTime = options.leaderGroupOptions.leaseTime,
            delegate = delegate,
            classifier = ConsulLeaderElector.ERROR_CLASSIFIER,
        )

        return try {
            AopScopeAccess.withPushedSync(lockHandle) {
                AopScopeAccess.setCapture(lockHandle)
                try {
                    action()
                } finally {
                    AopScopeAccess.clearCapture()
                }
            }
        } finally {
            watchdog.close()
            release(handle)
        }
    }

    private fun <T> runAsyncWithSlot(
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
            // Group auto-extension is disabled; explicit LockExtender renews the Consul session.
            enabled = false,
            leaseTime = options.leaderGroupOptions.leaseTime,
            delegate = delegate,
            classifier = ConsulLeaderElector.ERROR_CLASSIFIER,
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
        val electedAt = Instant.now()
        val leaseUntil = electedAt.plusMillis(options.leaderGroupOptions.leaseTime.inWholeMilliseconds)
        val ownerToken = Base58.randomString(12)
        val payload = ConsulOwnerPayload(
            ownerToken = ownerToken,
            auditLeaderId = auditLeaderId ?: options.leaderGroupOptions.nodeId,
            nodeId = options.leaderGroupOptions.nodeId,
            electedAt = electedAt,
            leaseUntil = leaseUntil,
        )
        val sessionId = try {
            lockClient.createSession(
                name = "${options.sessionNamePrefix}-${options.leaderGroupOptions.nodeId}",
                ttl = options.leaderGroupOptions.leaseTime,
                lockDelay = options.lockDelay,
            ).getWithinRequestTimeout(lockClient)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: Exception) {
            throw LeaderElectionException("Failed to create Consul group session. lockName=$lockName", e)
        }

        return try {
            acquireWithinWaitTime(lockName, sessionId, payload)?.let { (slot, key) ->
                ConsulLeaseHandle(
                    lockName = lockName,
                    key = key,
                    sessionId = sessionId,
                    ownerToken = ownerToken,
                    auditLeaderId = payload.auditLeaderId,
                    nodeId = payload.nodeId,
                    electedAt = electedAt,
                    leaseUntil = leaseUntil,
                    slotId = slot.toString(),
                )
            } ?: run {
                destroySession(sessionId)
                log.debug { "Consul leader group slot acquisition skipped by contention. lockName=$lockName" }
                null
            }
        } catch (e: Exception) {
            destroySession(sessionId)
            throw LeaderElectionException("Failed to acquire Consul group slot. lockName=$lockName", e)
        }
    }

    private fun acquireWithinWaitTime(
        lockName: String,
        sessionId: ConsulSessionId,
        ownerPayload: ConsulOwnerPayload,
    ): Pair<Int, String>? {
        val timeoutNanos = options.leaderGroupOptions.waitTime.inWholeNanoseconds
        val deadline = System.nanoTime() + timeoutNanos
        val startSlot = ThreadLocalRandom.current().nextInt(maxLeaders)
        val renewDelayNanos = ConsulSessionTtl.renewDelay(options.leaderGroupOptions.leaseTime).inWholeNanoseconds
        var lastRenewNanos = System.nanoTime()
        val payloadJson = ownerPayload.toJson()

        do {
            try {
                for (attempt in 0 until maxLeaders) {
                    val slot = (startSlot + attempt) % maxLeaders
                    val key = lockClient.groupLockKey(lockName, slot)
                    if (lockClient.acquire(key, sessionId, payloadJson).getWithinRequestTimeout(lockClient)) {
                        return slot to key
                    }
                }
                if (timeoutNanos == 0L || System.nanoTime() >= deadline) {
                    return null
                }
                val now = System.nanoTime()
                if (now - lastRenewNanos >= renewDelayNanos) {
                    lockClient.renewSession(sessionId).getWithinRequestTimeout(lockClient)
                    lastRenewNanos = now
                }
                Thread.sleep(minOf(50.milliseconds.inWholeMilliseconds, remainingMillis(deadline)).coerceAtLeast(1))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        } while (true)
    }

    private fun release(handle: ConsulLeaseHandle) {
        if (!handle.markReleased()) {
            return
        }

        val remaining = remainingMinLeaseTime(handle.acquiredAtNanos, options.leaderGroupOptions.minLeaseTime)
        if (remaining.isPositive()) {
            try {
                Thread.sleep(remaining.inWholeMilliseconds)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        runCatching { lockClient.release(handle.key, handle.sessionId).getWithinRequestTimeout(lockClient) }
            .onFailure { e -> log.warn(e) { "Failed to release Consul group slot. lockName=${handle.lockName}" } }
        destroySession(handle.sessionId)
    }

    private fun currentLeaders(lockName: String): List<LeaderLease> =
        (0 until maxLeaders).mapNotNull { slot ->
            runCatching {
                val entry = lockClient.read(lockClient.groupLockKey(lockName, slot)).getWithinRequestTimeout(lockClient)
                    ?: return@runCatching null
                if (entry.sessionId == null) {
                    return@runCatching null
                }
                val lease = entry.value?.let { ConsulOwnerPayload.fromJson(it)?.toLeaderLease(slot) }
                if (lease == null) {
                    log.warn {
                        "Consul group state ignored because owner payload is missing or invalid. " +
                            "lockName=$lockName, slot=$slot, sessionId=${entry.sessionId.value}"
                    }
                }
                lease
            }.getOrElse { e ->
                if (e is InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                log.warn(e) { "Consul group state query failed. lockName=$lockName, slot=$slot" }
                null
            }
        }

    private fun destroySession(sessionId: ConsulSessionId) {
        runCatching { lockClient.destroySession(sessionId).getWithinRequestTimeout(lockClient) }
            .onFailure { e -> log.warn(e) { "Failed to destroy Consul group session. sessionId=${sessionId.value}" } }
    }
}

private fun Throwable?.unwrapCompletionException(): Throwable? =
    if (this is CompletionException && cause != null) cause else this

private fun remainingMillis(deadlineNanos: Long): Long =
    ((deadlineNanos - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L).coerceAtLeast(1L)

/**
 * Runs [action] only when this Consul endpoint acquires a group leader slot.
 */
fun <T> ConsulEndpoint.runIfLeaderGroup(
    lockName: String,
    options: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
    action: () -> T,
): T? = ConsulLeaderGroupElector(this, options).runIfLeader(lockName, action)

/**
 * Runs [action] asynchronously only when this Consul endpoint acquires a group leader slot.
 */
fun <T> ConsulEndpoint.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ConsulLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)
