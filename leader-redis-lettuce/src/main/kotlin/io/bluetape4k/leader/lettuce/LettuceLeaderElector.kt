package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
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

/**
 * `StatefulRedisConnection` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun StatefulRedisConnection<String, String>.leaderElection(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): LettuceLeaderElector = LettuceLeaderElector(this, options)


/**
 * `LettuceLeaderElector`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property connection Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property historyRecorder Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class LettuceLeaderElector @JvmOverloads constructor(
    private val connection: StatefulRedisConnection<String, String>,
    private val options: LeaderElectionOptions = LeaderElectionOptions.Default,
    private val historyRecorder: SafeLeaderHistoryRecorder? = null,
): LeaderElector {

    companion object: KLogging() {
        internal const val LETTUCE_FACTORY_BEAN_NAME = "lettuce-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(LettuceBackendErrorClassifier)
    }

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        runImpl(lockName, auditLeaderId = null, action)

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        runImpl(slot.lockName, auditLeaderId = slot.leaderId, action)

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
            }.onFailure { log.warn(it) { "Fail to release lock. lockName=$lockName" } }
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
        return runAsyncIfLeader(slot, executor) {
            elected.set(true)
            action()
        }.handle { value, failure ->
            when {
                failure != null && elected.get() -> failure.toActionFailedResult()
                failure != null -> throw failure.asCompletionException()
                elected.get() -> LeaderRunResult.Elected(value, leaderId = slot.leaderId)
                else -> LeaderRunResult.Skipped
            }
        }
    }

    private fun <T> runAsyncImpl(
        lockName: String,
        auditLeaderId: String?,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        lockName.requireNotBlank("lockName")

        val lock = LettuceLock(connection, lockName, options.leaseTime)
        return lock.tryLockAsync(options.waitTime, options.leaseTime).thenComposeAsync({ acquired ->
            if (!acquired) {
                log.debug { "리더 선출 실패 (슬롯 없음, async): lockName=$lockName" }
                CompletableFuture.completedFuture(null)
            } else {
                val startedAt = Instant.now()
                val acquiredAtNanos = System.nanoTime()
                val token = lock.currentToken() ?: error("token missing after tryLock — lockName=$lockName")
                val delegate = LettuceLockExtendDelegate(lock)
                val watchdog =
                    LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate, ERROR_CLASSIFIER)
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

                log.debug { "리더 선출 성공 (async): lockName=$lockName" }
                val actionFuture: CompletableFuture<T> = try {
                    AopScopeAccess.withPushedSync(handle) { action() }
                } catch (e: Throwable) {
                    return@thenComposeAsync releaseAndPropagate<T>(
                        lock, lockName, watchdog, acquiredAtNanos, effectiveKey, e, null
                    )
                }

                actionFuture.handle<Pair<T?, Throwable?>> { value, error ->
                    Pair(value, error)
                }.thenCompose { (value, error) ->
                    releaseAndPropagate(lock, lockName, watchdog, acquiredAtNanos, effectiveKey, error, value)
                }
            }
        }, executor)
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
        watchdog.close()
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

        return lock.unlockAsync(options.minLeaseTime, acquiredAtNanos)
            .exceptionally { releaseError ->
                log.warn(releaseError) { "Fail to release lock. lockName=$lockName" }
            }
            .thenCompose {
                if (error != null) {
                    CompletableFuture.failedFuture(error)
                } else {
                    CompletableFuture.completedFuture<T?>(value)
                }
            }
    }
}
