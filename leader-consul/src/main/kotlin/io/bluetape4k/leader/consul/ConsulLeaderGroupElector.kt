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
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.consul.internal.ConsulLeaseHandle
import io.bluetape4k.leader.consul.internal.ConsulLockClient
import io.bluetape4k.leader.consul.internal.ConsulLockExtendDelegate
import io.bluetape4k.leader.consul.internal.ConsulOwnerPayload
import io.bluetape4k.leader.consul.internal.ConsulSessionId
import io.bluetape4k.leader.consul.internal.ConsulSessionTtl
import io.bluetape4k.leader.consul.internal.JavaHttpConsulLockClient
import io.bluetape4k.leader.consul.internal.getWithinRequestTimeout
import io.bluetape4k.leader.internal.LeaderFutureBridge
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `ConsulLeaderGroupElector`는 Consul backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property lockClient Consul backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Consul backend 호출과 상태 계산에 사용하는 속성입니다.
 */
// Group elector는 sync/async, 상태 조회, lease lifecycle, diagnostics 계약을 함께 구현합니다.
@Suppress("TooManyFunctions")
class ConsulLeaderGroupElector private constructor(
    private val lockClient: ConsulLockClient,
    val options: ConsulLeaderGroupElectionOptions,
) : LeaderGroupElector,
    LeaderBackendDiagnosticsProvider by ConsulLeaderBackendDiagnostics {

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
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        return LeaderFutureBridge.map(runAsyncIfLeader(slot, executor) {
            elected = true
            cancellationRelay.invoke(action)
        }, cancellationRelay) { value, failure ->
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

    @Suppress("TooGenericExceptionCaught")
    private fun <T> runAsyncWithSlot(
        lockName: String,
        auditLeaderId: String?,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val acquiredRef = AtomicReference<ConsulLeaseHandle?>()
        val lifecycleStarted = AtomicBoolean()
        val rejectionCleanupClaimed = AtomicBoolean()
        val releaseIfUnclaimed: () -> Unit = {
            val handle = acquiredRef.get()
            if (handle != null && !lifecycleStarted.get() && rejectionCleanupClaimed.compareAndSet(false, true)) {
                release(handle)
            }
        }
        val acquisitionFuture = CompletableFuture.supplyAsync({
            acquire(lockName, auditLeaderId).also { handle ->
                if (handle != null) acquiredRef.set(handle)
            }
        }, executor)
        val pipelineFuture: CompletableFuture<T?> = try {
            acquisitionFuture.thenComposeAsync({ handle ->
                if (handle == null) {
                    CompletableFuture.completedFuture(null)
                } else {
                    lifecycleStarted.set(true)
                    runAcquiredAsync(handle, executor, action)
                }
            }, executor)
        } catch (error: Throwable) {
            acquisitionFuture.whenComplete { handle, _ ->
                if (handle != null) releaseIfUnclaimed()
            }
            CompletableFuture.failedFuture(error)
        }
        pipelineFuture.whenComplete { _, failure ->
            if (failure != null) releaseIfUnclaimed()
        }
        acquisitionFuture.whenComplete { handle, _ ->
            if (handle != null && pipelineFuture.isCancelled) releaseIfUnclaimed()
        }
        return pipelineFuture
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun <T> runAcquiredAsync(
        handle: ConsulLeaseHandle,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val delegate = ConsulLockExtendDelegate(lockClient, handle)
        val watchdog = try {
            LeaderLeaseAutoExtender.start(
                // Group auto-extension is disabled; explicit LockExtender renews the Consul session.
                enabled = false,
                leaseTime = options.leaderGroupOptions.leaseTime,
                delegate = delegate,
                classifier = ConsulLeaderElector.ERROR_CLASSIFIER,
            )
        } catch (e: Throwable) {
            release(handle)
            return CompletableFuture.failedFuture(e)
        }
        val actionFuture = try {
            action()
        } catch (e: Throwable) {
            watchdog.close()
            release(handle)
            return CompletableFuture.failedFuture(e)
        }

        return actionFuture.handle { value, failure ->
            watchdog.close()
            release(handle)
            val cause = failure.unwrapCompletionException()
            if (cause != null) {
                throw CompletionException(cause)
            }
            value
        }
    }

    // Session creation and slot acquisition have separate cleanup and interruption branches;
    // the explicit branches preserve the backend/session lifecycle contract.
    @Suppress("ThrowsCount")
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
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
        } catch (e: CancellationException) {
            destroySession(sessionId)
            throw e
        } catch (e: InterruptedException) {
            destroySession(sessionId)
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            destroySession(sessionId)
            throw LeaderElectionException("Failed to acquire Consul group slot. lockName=$lockName", e)
        }
    }

    @Suppress("RethrowCaughtException", "NestedBlockDepth")
    private fun acquireWithinWaitTime(
        lockName: String,
        sessionId: ConsulSessionId,
        ownerPayload: ConsulOwnerPayload,
    ): Pair<Int, String>? {
        val timeoutNanos = options.leaderGroupOptions.waitTime.inWholeNanoseconds
        val deadline = System.nanoTime() + timeoutNanos
        val renewDelayNanos = ConsulSessionTtl.renewDelay(options.leaderGroupOptions.leaseTime).inWholeNanoseconds
        var lastRenewNanos = System.nanoTime()
        val payloadJson = ownerPayload.toJson()
        val slotsPerAttempt = consulGroupSlotProbeCount(maxLeaders)

        do {
            try {
                val startSlot = ThreadLocalRandom.current().nextInt(maxLeaders)
                for (attempt in 0 until slotsPerAttempt) {
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
                Thread.sleep(consulGroupAcquireDelayMillis(deadline))
            } catch (e: InterruptedException) {
                throw e
            }
        } while (true)
    }

    private fun release(handle: ConsulLeaseHandle) {
        if (!handle.markReleased()) {
            return
        }

        val remaining = remainingMinLeaseTime(handle.acquiredAtNanos, options.leaderGroupOptions.minLeaseTime)
        var interruption: InterruptedException? = null
        if (remaining.isPositive()) {
            try {
                Thread.sleep(remaining.inWholeMilliseconds)
            } catch (e: InterruptedException) {
                interruption = e
            }
        }

        runCatching { lockClient.release(handle.key, handle.sessionId).getWithinRequestTimeout(lockClient) }
            .onFailure { e -> log.warn(e) { "Failed to release Consul group slot. lockName=${handle.lockName}" } }
        destroySession(handle.sessionId)
        interruption?.let {
            Thread.currentThread().interrupt()
            throw it
        }
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

/**
 * `선언` 호출은 Consul backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
fun <T> ConsulEndpoint.runIfLeaderGroup(
    lockName: String,
    options: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
    action: () -> T,
): T? = ConsulLeaderGroupElector(this, options).runIfLeader(lockName, action)

/**
 * `선언` 호출은 Consul backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
fun <T> ConsulEndpoint.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ConsulLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)
