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
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Creates a [LettuceLeaderElector] instance from this [StatefulRedisConnection].
 *
 * ```kotlin
 * val election = connection.leaderElection()
 * val result = election.runIfLeader("daily-job") { "done" }
 * ```
 *
 * @param options leader election options (default: [LeaderElectionOptions.Default])
 * @return [LettuceLeaderElector] instance
 */
fun StatefulRedisConnection<String, String>.leaderElection(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): LettuceLeaderElector = LettuceLeaderElector(this, options)


/**
 * Leader election implementation using the Lettuce Redis client.
 *
 * Uses [LettuceLock] to elect a single leader in a distributed environment.
 * Supports both synchronous ([runIfLeader]) and asynchronous ([runAsyncIfLeader]) execution.
 *
 * ## Behavior / Contract (T7 PR 2)
 *
 * - After acquire, creates [LettuceLockExtendDelegate] and shares the same reference
 *   with [LeaderLockHandle.Real] and the watchdog.
 * - When the aspect extends the lease via `LockAssert` / `LockExtender`, it uses the same delegate reference (AC-15).
 * - [LeaderLeaseAutoExtender.start] also receives the same delegate to activate R2 watchdog skip semantics.
 *
 * ```kotlin
 * val election = LettuceLeaderElector(connection)
 * val result = election.runIfLeader("daily-job") { "done" }
 * ```
 *
 * @param connection Lettuce [StatefulRedisConnection] (StringCodec-based)
 * @param options    leader election options (waitTime, leaseTime)
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
                val effectiveKey: LeaderHistoryKey? = key ?: record?.let { LeaderHistoryKey(lockName = lockName, token = token) }

                log.debug { "리더 선출 성공 (async): lockName=$lockName" }
                val actionFuture: CompletableFuture<T> = try {
                    action()
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
            error == null -> historyKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }
            error is java.util.concurrent.CancellationException -> { /* cancelled — no audit */ }
            else -> historyKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, error) }
        }

        return lock.unlockAsync(options.minLeaseTime, acquiredAtNanos)
            .exceptionally { releaseError ->
                log.warn(releaseError) { "Fail to release lock. lockName=$lockName" }
                Unit
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
