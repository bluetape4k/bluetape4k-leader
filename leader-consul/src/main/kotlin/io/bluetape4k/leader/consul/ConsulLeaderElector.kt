package io.bluetape4k.leader.consul

import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAcquirer
import io.bluetape4k.leader.LeaderLeaseAcquirerSupport
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.consul.internal.ConsulBackendErrorClassifier
import io.bluetape4k.leader.consul.internal.ConsulLeaseHandle
import io.bluetape4k.leader.consul.internal.ConsulLockClient
import io.bluetape4k.leader.consul.internal.ConsulLockExtendDelegate
import io.bluetape4k.leader.consul.internal.ConsulOwnerPayload
import io.bluetape4k.leader.consul.internal.ConsulSessionId
import io.bluetape4k.leader.consul.internal.ConsulSessionTtl
import io.bluetape4k.leader.consul.internal.JavaHttpConsulLockClient
import io.bluetape4k.leader.consul.internal.getWithinRequestTimeout
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.LeaderElectorLeaseAdapter
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * `ConsulLeaderElector`는 Consul KV key와 session을 이용해 단일 leader의 blocking/async 실행 수명을 관리합니다.
 *
 * @property lockClient session 생성·KV acquire/release·renew/read/destroy를 Consul API에 위임하는 클라이언트입니다.
 * @property options lease 범위(10초~24시간), KV prefix, session prefix, lock delay를 포함한 단일 설정입니다.
 */
// Single elector는 sync/async, audit state, lease lifecycle, diagnostics 계약을 함께 구현합니다.
@Suppress("TooManyFunctions")
class ConsulLeaderElector private constructor(
    private val lockClient: ConsulLockClient,
    val options: ConsulLeaderElectionOptions,
): LeaderElector,
   LeaderBackendDiagnosticsProvider by ConsulLeaderBackendDiagnostics,
   LeaderLeaseAcquirerSupport {

    override val leaseAcquirerDelegate: LeaderLeaseAcquirer by lazy {
        LeaderElectorLeaseAdapter({ this }, options.leaderOptions)
    }

    constructor(
        endpoint: ConsulEndpoint,
        options: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
    ): this(JavaHttpConsulLockClient(endpoint, options.keyPrefix), options)

    companion object: KLogging() {
        internal const val CONSUL_FACTORY_BEAN_NAME = "consul-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ConsulBackendErrorClassifier)

        internal fun create(
            lockClient: ConsulLockClient,
            options: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
        ): ConsulLeaderElector =
            ConsulLeaderElector(lockClient, options)
    }

    private enum class AsyncLifecycle {
        WAITING,
        STARTED,
        CLEANUP,
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

    override val supportsAuditLeaderState: Boolean = true

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

    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")
    private fun <T> runAsyncWithLock(
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
                else -> completableFutureOf(
                    Unit
                )
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
                    failedCompletableFutureOf(
                        CancellationException("leader result future was cancelled before action"),
                    )
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
                    val cause = checkNotNull(failure.unwrapCompletionException())
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
                options.leaderOptions.autoExtend,
                options.leaderOptions.leaseTime,
                delegate,
                ERROR_CLASSIFIER,
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

    // Session creation and lock acquisition have separate cleanup and interruption branches;
    // the explicit branches preserve the backend/session lifecycle contract.
    @Suppress("ThrowsCount")
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            throw LeaderElectionException("Failed to create Consul session. lockName=$lockName", e)
        }

        val acquired = try {
            acquireWithinWaitTime(key, sessionId, payload.toJson())
        } catch (e: CancellationException) {
            destroySession(sessionId)
            throw e
        } catch (e: InterruptedException) {
            destroySession(sessionId)
            Thread.currentThread().interrupt()
            throw e
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

    @Suppress("RethrowCaughtException")
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
                throw e
            }
        } while (true)
    }

    private fun release(handle: ConsulLeaseHandle) {
        if (!handle.markReleased()) {
            return
        }

        val remaining = remainingMinLeaseTime(handle.acquiredAtNanos, options.leaderOptions.minLeaseTime)
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
            log.warn(e) { "Failed to release Consul leader lock. lockName=${handle.lockName}" }
        }
        destroySession(handle.sessionId)
        interruption?.let {
            Thread.currentThread().interrupt()
            throw it
        }
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
 * [this] Consul endpoint에서 [lockName]을 획득했을 때만 [action]을 실행합니다.
 * 경합이면 null을 반환하고, session과 KV ownership은 action 종료 후 정리합니다.
 */
fun <T> ConsulEndpoint.runIfLeader(
    lockName: String,
    options: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
    action: () -> T,
): T? = ConsulLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * [this] Consul endpoint에서 [lockName]을 비동기로 획득한 뒤 [action]을 실행합니다.
 * 기본 [executor]는 virtual thread executor이며 경합 시 null로 완료합니다.
 */
fun <T> ConsulEndpoint.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ConsulLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)
