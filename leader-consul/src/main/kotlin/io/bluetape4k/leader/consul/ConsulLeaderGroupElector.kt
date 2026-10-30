package io.bluetape4k.leader.consul

import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.javatimes.millis
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.AsyncLifecycle
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
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.checkNotNull
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * `ConsulLeaderGroupElector`는 Consul KV slot key와 session을 이용해 여러 leader의 blocking/async 실행을 관리합니다.
 *
 * @property lockClient session 생성·그룹 KV acquire/release·renew/read/destroy를 Consul API에 위임하는 클라이언트입니다.
 * @property options maxLeaders, lease 범위(10초~24시간), KV prefix, session prefix, lock delay를 포함한 그룹 설정입니다.
 */
// Group elector는 sync/async, 상태 조회, lease lifecycle, diagnostics 계약을 함께 구현합니다.
@Suppress("TooManyFunctions")
class ConsulLeaderGroupElector private constructor(
    private val lockClient: ConsulLockClient,
    val options: ConsulLeaderGroupElectionOptions,
): LeaderGroupElector,
   LeaderBackendDiagnosticsProvider by ConsulLeaderBackendDiagnostics {

    constructor(
        endpoint: ConsulEndpoint,
        options: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
    ): this(JavaHttpConsulLockClient(endpoint, options.keyPrefix), options)

    companion object: KLogging() {
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
                cause != null            -> throw CompletionException(cause)
                elected                  -> LeaderRunResult.Elected(value, leaderId = slot.leaderId)
                else                     -> LeaderRunResult.Skipped
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

    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")
    private fun <T> runAsyncWithSlot(
        lockName: String,
        auditLeaderId: String?,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val lifecycle = AtomicReference(AsyncLifecycle.WAITING)
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        val cleanupBarrier = AsyncLeaseCleanupBarrier(::release)

        val beginCleanup: () -> CompletableFuture<Unit> = {
            when {
                lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.CLEANUP) -> cleanupBarrier.request()
                lifecycle.get() == AsyncLifecycle.CLEANUP -> cleanupBarrier.request()
                else                                      -> completableFutureOf(Unit)
            }
        }
        val acquisitionFuture = futureOf(executor) {
            var handle: ConsulLeaseHandle? = null
            try {
                acquire(lockName, auditLeaderId).also { handle = it }
            } finally {
                cleanupBarrier.completeAcquisition(handle)
            }
        }

        val pipelineFuture: CompletableFuture<T?> = try {
            acquisitionFuture.thenComposeAsync({ handle ->
                if (handle == null) {
                    completableFutureOf(null)
                } else if (!lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.STARTED)) {
                    failedCompletableFutureOf(CancellationException("leader result future was cancelled before action"))
                } else {
                    runAcquiredAsync(handle, cancellationRelay, action)
                }
            }, executor)
        } catch (error: Throwable) {
            failedCompletableFutureOf(error)
        }

        val ordered = LeaderFutureBridge.flatMap(pipelineFuture) { value, failure ->
            if (failure == null) {
                completableFutureOf(value)
            } else {
                beginCleanup().handle { _, cleanupFailure ->
                    val cause = failure.unwrapCompletionException().checkNotNull("failure")
                    cleanupFailure?.unwrapCompletionException()?.let(cause::addSuppressed)
                    throw CompletionException(cause)
                }
            }
        }
        return LeaderFutureBridge.propagateCancellation(ordered, cancellationRelay)
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun <T> runAcquiredAsync(
        handle: ConsulLeaseHandle,
        cancellationRelay: LeaderFutureBridge.CancellationRelay,
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
            return AsyncLeaseCleanupDispatcher.failAfter(e) { release(handle) }
        }
        val actionFuture = try {
            cancellationRelay.invoke(action)
        } catch (e: Throwable) {
            return AsyncLeaseCleanupDispatcher.failAfter(e) {
                watchdog.close()
                release(handle)
            }
        }

        return AsyncLeaseCleanupDispatcher.completeAfter(
            source = actionFuture,
            cleanup = {
                watchdog.close()
                release(handle)
            },
        ) { value, failure ->
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
        val leaseUntil = electedAt + options.leaderGroupOptions.leaseTime.inWholeMilliseconds.millis()
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

        runCatching {
            lockClient.release(handle.key, handle.sessionId).getWithinRequestTimeout(lockClient)
        }.onFailure { e ->
            log.warn(e) { "Failed to release Consul group slot. lockName=${handle.lockName}" }
        }
        destroySession(handle.sessionId)
        interruption?.let {
            Thread.currentThread().interrupt()
            throw it
        }
    }

    private fun currentLeaders(lockName: String): List<LeaderLease> = List(maxLeaders) { slot ->
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
    }.filterNotNull()

    private fun destroySession(sessionId: ConsulSessionId) {
        runCatching {
            lockClient.destroySession(sessionId).getWithinRequestTimeout(lockClient)
        }.onFailure { e ->
            log.warn(e) { "Failed to destroy Consul group session. sessionId=${sessionId.value}" }
        }
    }
}

private fun Throwable?.unwrapCompletionException(): Throwable? =
    if (this is CompletionException && cause != null) cause else this

/**
 * [this] Consul endpoint에서 그룹 [lockName]의 슬롯을 획득했을 때만 [action]을 실행합니다.
 * 사용할 슬롯이 없으면 null을 반환하고, action 종료 후 session과 슬롯 ownership을 정리합니다.
 */
fun <T> ConsulEndpoint.runIfLeaderGroup(
    lockName: String,
    options: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
    action: () -> T,
): T? = ConsulLeaderGroupElector(this, options).runIfLeader(lockName, action)

/**
 * [this] Consul endpoint에서 그룹 [lockName]의 슬롯을 비동기로 획득한 뒤 [action]을 실행합니다.
 * 기본 [executor]에서 처리하며 슬롯을 얻지 못하면 null로 완료합니다.
 */
fun <T> ConsulEndpoint.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ConsulLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)
