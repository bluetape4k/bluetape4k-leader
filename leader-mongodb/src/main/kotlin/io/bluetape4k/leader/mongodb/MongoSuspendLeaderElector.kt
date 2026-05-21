package io.bluetape4k.leader.mongodb

import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.mongodb.internal.MongoBackendErrorClassifier
import io.bluetape4k.leader.mongodb.internal.MongoSuspendLockExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoSuspendLock
import io.bluetape4k.leader.mongodb.lock.validateMongoLockName
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.bson.Document
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Coroutine-based single leader election implementation using MongoDB distributed locks.
 *
 * Token-based lock (`findOneAndUpdate` + TTL) — safe regardless of coroutine thread switches.
 *
 * ## ExtendDelegate Integration (T9 PR 4 / Issue #79)
 *
 * - After acquire, creates a [MongoSuspendLockExtendDelegate] shared by [LeaderLockHandle.Real] and the watchdog (AC-15).
 * - The aspect's `LockExtenderSuspend.extendActiveLockSuspend` uses the same delegate reference.
 * - Propagates the handle into the coroutine context via
 *   `withContext(AopScopeAccess.createLockHandleElement(handle))`.
 * - The autoExtend option is handled by the [LeaderLeaseAutoExtender] watchdog — no separate `launch` watchdog.
 *
 * ```kotlin
 * val election = MongoSuspendLeaderElector(coroutineDatabase.getCollection("bluetape4k_leader_locks"))
 * val result = election.runIfLeader("daily-job") {
 *     delay(100)
 *     processData()
 * }
 * ```
 *
 * **Cancellation safety:** Even on coroutine cancellation, `withContext(NonCancellable)` guarantees
 * watchdog close and lock release.
 *
 * @param collection coroutine [MongoCollection] storing the lock state
 * @param options leader election options
 */
class MongoSuspendLeaderElector private constructor(
    private val collection: MongoCollection<Document>,
    val options: MongoLeaderElectionOptions,
    private val historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
) : SuspendLeaderElector {

    companion object : KLoggingChannel() {
        internal const val MONGO_SUSPEND_FACTORY_BEAN_NAME = "mongo-suspend-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(MongoBackendErrorClassifier)

        suspend operator fun invoke(
            collection: MongoCollection<Document>,
            options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
            historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
        ): MongoSuspendLeaderElector {
            MongoSuspendLock.ensureIndexes(collection)
            return MongoSuspendLeaderElector(collection, options, historyRecorder)
        }
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        validateMongoLockName(lockName)
        val lock = MongoSuspendLock(collection, lockName, options.retryDelay)
        log.debug { "리더 승격을 요청합니다 (suspend). lockName=$lockName" }

        if (!lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime)) {
            log.debug { "리더 승격 실패 (슬롯 없음, suspend). lockName=$lockName" }
            return null
        }

        val startedAt = Instant.now()
        val acquiredAtNanos = System.nanoTime()
        val delegate: SuspendExtendDelegate = MongoSuspendLockExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = MONGO_SUSPEND_FACTORY_BEAN_NAME,
        )
        val handle = LeaderLockHandle.real(
            identity = identity,
            token = lock.token,
            acquiredAtNanos = acquiredAtNanos,
            extendDelegate = delegate,
        )
        val watchdog = LeaderLeaseAutoExtender.start(
            options.leaderOptions.autoExtend,
            options.leaderOptions.leaseTime,
            delegate,
            ERROR_CLASSIFIER,
        )

        val record = historyRecorder?.let {
            LeaderLockHistoryRecord(
                lockName = lockName,
                token = lock.token,
                kind = LockIdentity.AnnotationKind.SINGLE,
                acquiredAt = startedAt,
                lockedUntil = startedAt.plusMillis(options.leaderOptions.leaseTime.inWholeMilliseconds),
            )
        }
        val key = record?.let { historyRecorder.recordAcquired(it) }
        val effectiveKey: LeaderHistoryKey? =
            key ?: record?.let { LeaderHistoryKey(lockName = lockName, token = lock.token) }

        log.debug { "리더로 승격하여 suspend 작업을 수행합니다. lockName=$lockName" }
        try {
            return try {
                val result = withContext(AopScopeAccess.createLockHandleElement(handle)) {
                    action()
                }
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
            // NonCancellable: 코루틴 취소 시에도 watchdog close + 락 해제가 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog.close()
                try {
                    lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos)
                    log.debug { "리더 권한을 반납했습니다 (suspend). lockName=$lockName" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "락 해제 실패 (suspend). lockName=$lockName" }
                }
            }
        }
    }
}

/**
 * Runs the suspend [action] only when elected as leader using a MongoDB distributed lock.
 */
suspend fun <T> MongoCollection<Document>.suspendRunIfLeader(
    lockName: String,
    options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? = MongoSuspendLeaderElector(this, options).runIfLeader(lockName, action)
