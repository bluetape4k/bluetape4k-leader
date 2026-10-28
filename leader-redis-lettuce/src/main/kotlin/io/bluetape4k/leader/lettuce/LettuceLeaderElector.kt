package io.bluetape4k.leader.lettuce

import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.AsyncLifecycle
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAcquirer
import io.bluetape4k.leader.LeaderLeaseAcquirerSupport
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.LeaderElectorLeaseAdapter
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.lettuce.internal.LettuceBackendErrorClassifier
import io.bluetape4k.leader.lettuce.internal.LettuceLockExtendDelegate
import io.bluetape4k.leader.lettuce.lock.LettuceLock
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 주어진 Redis 연결과 단일 선출 정책으로 `LettuceLeaderElector`를 생성합니다. */
fun StatefulRedisConnection<String, String>.leaderElection(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): LettuceLeaderElector = LettuceLeaderElector(this, options)


/**
 * `LettuceLeaderElector`는 Redis Lettuce의 blocking 단일 선출을 제공하며 lockName의 lease 획득·연장·해제를 관리합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property connection lock key의 token을 저장·갱신·삭제하는 Redis 연결입니다.
 * @property options lock 대기·lease·최소 lease·auto-extend 동작을 정하는 단일 선출 정책입니다.
 * @property historyRecorder 획득·완료·실패 이력을 best-effort로 전달할 선택적 recorder입니다. `null`이면 이력을 기록하지 않습니다.
 */
// Single elector는 sync/async, lease lifecycle, diagnostics 계약을 함께 구현합니다.
@Suppress("TooManyFunctions")
class LettuceLeaderElector @JvmOverloads constructor(
    private val connection: StatefulRedisConnection<String, String>,
    private val options: LeaderElectionOptions = LeaderElectionOptions.Default,
    private val historyRecorder: SafeLeaderHistoryRecorder? = null,
): LeaderElector,
   LeaderBackendDiagnosticsProvider by LettuceLeaderBackendDiagnostics(connection),
   LeaderLeaseAcquirerSupport {

    override val leaseAcquirerDelegate: LeaderLeaseAcquirer by lazy {
        LeaderElectorLeaseAdapter({ this }, options)
    }

    companion object: KLogging() {
        internal const val LETTUCE_FACTORY_BEAN_NAME = "lettuce-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(LettuceBackendErrorClassifier)
    }

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        runImpl(lockName, auditLeaderId = null, action)

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        runImpl(slot.lockName, auditLeaderId = slot.leaderId, action)

    @Suppress("ThrowsCount")
    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runImpl(slot.lockName, auditLeaderId = slot.leaderId) {
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

    private fun <T> runImpl(lockName: String, auditLeaderId: String?, action: () -> T): T? {
        lockName.requireNotBlank("lockName")

        val lock = LettuceLock(connection, lockName, options.leaseTime)
        val acquired = lock.tryLock(options.waitTime, options.leaseTime)
        if (!acquired) {
            log.debug { "리더 선출 실패 (슬롯 없음): lockName=$lockName" }
            return null
        }
        val startedAt = Instant.now()
        val acquiredAtNanos = System.nanoTime()
        val token = lock.currentToken() ?: error("token missing after tryLock — lockName=$lockName")
        val delegate = LettuceLockExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = LETTUCE_FACTORY_BEAN_NAME,
        )
        val handle = LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = acquiredAtNanos,
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId,
        )
        val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate, ERROR_CLASSIFIER)

        val record = historyRecorder?.let {
            LeaderLockHistoryRecord(
                lockName = lockName,
                token = token,
                kind = LockIdentity.AnnotationKind.SINGLE,
                acquiredAt = startedAt,
                lockedUntil = startedAt.plusMillis(options.leaseTime.inWholeMilliseconds),
            )
        }
        val key = record?.let { historyRecorder.recordAcquired(it) }
        val effectiveKey: LeaderHistoryKey? =
            key ?: record?.let { LeaderHistoryKey(lockName = lockName, token = token) }

        log.debug { "리더 선출 성공: lockName=$lockName" }
        try {
            return try {
                val result = AopScopeAccess.withPushedSync(handle) { action() }
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                effectiveKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, e) }
                throw e
            }
        } finally {
            watchdog.close()
            runCatching {
                if (lock.isHeldByCurrentInstance()) {
                    lock.unlock(options.minLeaseTime, acquiredAtNanos)
                }
            }.onFailure {
                log.warn(it) { "Fail to release lock. lockName=$lockName" }
            }
        }
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        runAsyncImpl(lockName, auditLeaderId = null, executor, action)

    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        runAsyncImpl(slot.lockName, auditLeaderId = slot.leaderId, executor, action)

    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        val elected = AtomicBoolean(false)
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        return LeaderFutureBridge.map(runAsyncIfLeader(slot, executor) {
            elected.set(true)
            cancellationRelay.invoke(action)
        }, cancellationRelay) { value, failure ->
            when {
                failure != null && elected.get() -> failure.toActionFailedResult()
                failure != null -> throw failure.asCompletionException()
                elected.get() -> LeaderRunResult.Elected(value, leaderId = slot.leaderId)
                else -> LeaderRunResult.Skipped
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> runAsyncImpl(
        lockName: String,
        auditLeaderId: String?,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        lockName.requireNotBlank("lockName")

        val lock = LettuceLock(connection, lockName, options.leaseTime)
        val acquiredAtNanos = AtomicLong()
        val lockAcquired = AtomicBoolean()
        val lifecycle = AtomicReference(AsyncLifecycle.WAITING)
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        val releaseAfterRejection: (Throwable) -> CompletableFuture<T?> = { failure ->
            if (
                lockAcquired.get() &&
                lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.CLEANUP)
            ) {
                lock.unlockAsync(options.minLeaseTime, acquiredAtNanos.get())
                    .exceptionally { releaseError ->
                        log.warn(releaseError) { "executor 거부 후 비동기 락 해제 실패. lockName=$lockName" }
                    }
                    .thenCompose { failedCompletableFutureOf(failure) }
            } else {
                failedCompletableFutureOf(failure)
            }
        }
        val acquisitionFuture = lock.tryLockAsync(options.waitTime, options.leaseTime).thenApply { acquired ->
            if (acquired) {
                acquiredAtNanos.set(System.nanoTime())
                lockAcquired.set(true)
            }
            acquired
        }
        val pipelineFuture = acquisitionFuture.thenComposeAsync({ acquired ->
            if (!acquired) {
                log.debug { "리더 선출 실패 (슬롯 없음, async): lockName=$lockName" }
                completableFutureOf(null)
            } else {
                val acquiredAt = acquiredAtNanos.get()
                if (!lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.STARTED)) {
                    failedCompletableFutureOf(CancellationException("leader action was cancelled before start"))
                } else try {
                    runAcquiredAsync(lock, lockName, auditLeaderId, acquiredAt, cancellationRelay, action)
                } catch (error: Throwable) {
                    lock.unlockAsync(options.minLeaseTime, acquiredAt)
                        .exceptionally { releaseError ->
                            error.addSuppressed(releaseError.unwrapCompletionCause())
                        }
                        .thenCompose { failedCompletableFutureOf(error) }
                }
            }
        }, executor)
        acquisitionFuture.whenComplete { acquired, _ ->
            if (acquired == true && pipelineFuture.isCancelled) {
                releaseAfterRejection(CancellationException("leader result future was cancelled before action"))
            }
        }
        return LeaderFutureBridge.flatMap(pipelineFuture, cancellationRelay) { value, failure ->
            if (failure != null) {
                releaseAfterRejection(failure.unwrapCompletionCause())
            } else {
                completableFutureOf(value)
            }
        }
    }

    private fun <T> runAcquiredAsync(
        lock: LettuceLock,
        lockName: String,
        auditLeaderId: String?,
        acquiredAtNanos: Long,
        cancellationRelay: LeaderFutureBridge.CancellationRelay,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val startedAt = Instant.now()
        val token = lock.currentToken() ?: error("token missing after tryLock — lockName=$lockName")
        val delegate = LettuceLockExtendDelegate(lock)
        val handle = LeaderLockHandle.real(
            identity = LockIdentity(lockName, LockIdentity.AnnotationKind.SINGLE, LETTUCE_FACTORY_BEAN_NAME),
            token = token,
            acquiredAtNanos = acquiredAtNanos,
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId,
        )
        val record = historyRecorder?.let {
            LeaderLockHistoryRecord(
                lockName = lockName,
                token = token,
                kind = LockIdentity.AnnotationKind.SINGLE,
                acquiredAt = startedAt,
                lockedUntil = startedAt.plusMillis(options.leaseTime.inWholeMilliseconds),
            )
        }
        val key = record?.let { historyRecorder.recordAcquired(it) }
        val effectiveKey = key ?: record?.let { LeaderHistoryKey(lockName = lockName, token = token) }
        val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate, ERROR_CLASSIFIER)

        log.debug { "리더 선출 성공 (async): lockName=$lockName" }
        val actionFuture = runCatching {
            cancellationRelay.invoke { AopScopeAccess.withPushedSync(handle) { action() } }
        }
            .getOrElse { error ->
                return releaseAndPropagate(lock, lockName, watchdog, acquiredAtNanos, effectiveKey, error, null)
            }
        return actionFuture.handle<Pair<T?, Throwable?>> { value, error -> Pair(value, error) }
            .thenCompose { (value, error) ->
                releaseAndPropagate(lock, lockName, watchdog, acquiredAtNanos, effectiveKey, error, value)
            }
    }

    private fun Throwable.unwrapCompletionCause(): Throwable =
        (this as? CompletionException)?.cause ?: this

    private fun Throwable.toActionFailedResult(): LeaderRunResult.ActionFailed {
        val cause = unwrapCompletionCause()
        if (cause is CancellationException) {
            throw cause
        }
        if (cause is java.util.concurrent.CancellationException) {
            throw cause
        }
        return LeaderRunResult.ActionFailed(cause)
    }

    private fun Throwable.asCompletionException(): CompletionException =
        this as? CompletionException ?: CompletionException(this)

    private fun <T> releaseAndPropagate(
        lock: LettuceLock,
        lockName: String,
        watchdog: AutoCloseable,
        acquiredAtNanos: Long,
        historyKey: LeaderHistoryKey?,
        error: Throwable?,
        value: T?,
    ): CompletableFuture<T?> {
        return LeaderLeaseAutoExtender.closeAsync(watchdog)
            .handle { _, closeFailure ->
                val closeCause = closeFailure?.unwrapCompletionCause()
                if (closeCause != null && error != null) {
                    error.addSuppressed(closeCause)
                }
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                when {
                    error == null -> historyKey?.let {
                        historyRecorder?.recordCompleted(
                            it,
                            finishedAt,
                            durationMs
                        )
                    }
                    error is java.util.concurrent.CancellationException -> { /* cancelled — no audit */
                    }
                    else -> historyKey?.let {
                        historyRecorder?.recordFailed(
                            it,
                            finishedAt,
                            durationMs,
                            error
                        )
                    }
                }
                if (error == null) closeCause else null
            }
            .thenCompose { closeFailure ->
                lock.unlockAsync(options.minLeaseTime, acquiredAtNanos)
                    .exceptionally { releaseError ->
                        log.warn(releaseError) { "Fail to release lock. lockName=$lockName" }
                    }
                    .thenCompose {
                        if (error != null) {
                            failedCompletableFutureOf(error)
                        } else if (closeFailure != null) {
                            failedCompletableFutureOf(closeFailure)
                        } else {
                            completableFutureOf<T?>(value)
                        }
                    }
            }
    }
}
