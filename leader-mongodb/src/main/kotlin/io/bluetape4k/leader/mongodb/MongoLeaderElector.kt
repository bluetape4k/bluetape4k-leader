package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.mongodb.internal.MongoBackendErrorClassifier
import io.bluetape4k.leader.mongodb.internal.MongoLockExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.leader.mongodb.lock.validateMongoLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.bson.Document
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Leader election implementation for a single leader using MongoDB distributed locks.
 *
 * Token-based lock using `findOneAndUpdate` upsert + TTL index —
 * not bound to any thread, safe in Virtual Thread environments.
 *
 * ## ExtendDelegate Integration (T9 PR 4 / Issue #79)
 *
 * - After acquire, creates a [MongoLockExtendDelegate] shared by [LeaderLockHandle.Real] and the watchdog (AC-15).
 * - When the aspect calls `LockExtender.extendActiveLock`, the same delegate applies the R6 filter
 *   (`expireAt > now`) before executing the extend.
 * - The autoExtend option is handled by the [LeaderLeaseAutoExtender] watchdog (R2 watchdog skip semantics guaranteed).
 *
 * ```kotlin
 * val election = MongoLeaderElector(database.getCollection("bluetape4k_leader_locks"))
 * val result = election.runIfLeader("daily-job") { processData() }
 * // result == return value of processData() (leader acquired) or null (not elected)
 * ```
 *
 * **WriteConcern:** In a Replica Set environment, configure the collection with `MAJORITY`.
 * `ACKNOWLEDGED` carries a split-brain risk during network partitions.
 *
 * @param collection [MongoCollection] storing the lock state (collection name: [MongoLock.LOCK_COLLECTION_NAME])
 * @param options leader election options
 */
class MongoLeaderElector private constructor(
    private val collection: MongoCollection<Document>,
    val options: MongoLeaderElectionOptions,
    private val historyRecorder: SafeLeaderHistoryRecorder? = null,
) : LeaderElector {

    companion object : KLogging() {
        internal const val MONGO_FACTORY_BEAN_NAME = "mongo-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(MongoBackendErrorClassifier)

        @JvmStatic
        @JvmOverloads
        operator fun invoke(
            collection: MongoCollection<Document>,
            options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
            historyRecorder: SafeLeaderHistoryRecorder? = null,
        ): MongoLeaderElector {
            MongoLock.ensureIndexes(collection)
            return MongoLeaderElector(collection, options, historyRecorder)
        }
    }

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        validateMongoLockName(lockName)
        val lock = MongoLock(collection, lockName, options.retryDelay)
        log.debug { "리더 승격을 요청합니다. lockName=$lockName" }

        if (!lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime)) {
            log.debug { "리더 승격 실패 (슬롯 없음). lockName=$lockName" }
            return null
        }

        val startedAt = Instant.now()
        val acquiredAtNanos = System.nanoTime()
        val delegate = MongoLockExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = MONGO_FACTORY_BEAN_NAME,
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

        log.debug { "리더로 승격하여 작업을 수행합니다. lockName=$lockName" }
        try {
            return try {
                val result = AopScopeAccess.withPushedSync(handle) { action() }
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                effectiveKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }
                result
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, e) }
                throw e
            }
        } finally {
            watchdog.close()
            runCatching { lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos) }
                .onSuccess { log.debug { "리더 권한을 반납했습니다. lockName=$lockName" } }
                .onFailure { e -> log.warn(e) { "락 해제 실패. lockName=$lockName" } }
        }
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        validateMongoLockName(lockName)
        val lock = MongoLock(collection, lockName, options.retryDelay)

        return lock
            .tryLockAsync(options.leaderOptions.waitTime, options.leaderOptions.leaseTime)
            .thenComposeAsync({ acquired ->
                if (!acquired) {
                    log.debug { "리더 승격 실패 (슬롯 없음, 비동기). lockName=$lockName" }
                    CompletableFuture.completedFuture(null)
                } else {
                    val startedAt = Instant.now()
                    val acquiredAtNanos = System.nanoTime()
                    val delegate = MongoLockExtendDelegate(lock)
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

                    log.debug { "리더로 승격하여 비동기 작업을 수행합니다. lockName=$lockName" }
                    val actionFuture = runCatching { action() }
                        .getOrElse { e ->
                            watchdog.close()
                            val finishedAt = Instant.now()
                            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                            effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, e) }
                            runCatching { lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos) }
                                .onFailure { ex -> log.warn(ex) { "락 해제 실패 (action 오류 경로). lockName=$lockName" } }
                            return@thenComposeAsync CompletableFuture.failedFuture(e)
                        }
                    actionFuture.whenCompleteAsync({ _, throwable ->
                        watchdog.close()
                        val finishedAt = Instant.now()
                        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                        when {
                            throwable == null -> effectiveKey?.let {
                                historyRecorder?.recordCompleted(it, finishedAt, durationMs)
                            }
                            throwable is java.util.concurrent.CancellationException -> { /* cancelled — no audit */ }
                            else -> effectiveKey?.let {
                                historyRecorder?.recordFailed(it, finishedAt, durationMs, throwable)
                            }
                        }
                        runCatching { lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos) }
                            .onSuccess { log.debug { "비동기 리더 권한을 반납했습니다. lockName=$lockName" } }
                            .onFailure { e -> log.warn(e) { "비동기 락 해제 실패. lockName=$lockName" } }
                    }, executor)
                }
            }, executor)
    }
}

/**
 * Runs [action] only when elected as leader using a MongoDB distributed lock.
 */
fun <T> MongoCollection<Document>.runIfLeader(
    lockName: String,
    options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    action: () -> T,
): T? = MongoLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * Runs the async [action] only when elected as leader using a MongoDB distributed lock.
 */
fun <T> MongoCollection<Document>.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = MongoLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)
