package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.mongodb.internal.MongoBackendErrorClassifier
import io.bluetape4k.leader.mongodb.internal.MongoSlotExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.leader.mongodb.lock.validateMongoLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.bson.Document
import java.time.Instant
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Multi-leader group election implementation using a MongoDB slot-based distributed semaphore.
 *
 * Simulates `maxLeaders` slots using N [MongoLock] instances keyed as `${lockName}:slot:N`.
 * The starting slot is randomized to avoid hotspots.
 *
 * ## ExtendDelegate Integration (T9 PR 4 / Issue #79)
 *
 * - Wraps each acquired per-slot [MongoLock] with [MongoSlotExtendDelegate], sharing the same
 *   reference with the watchdog (AC-15).
 * - When the aspect calls `LockExtender.extendActiveLock`, the same delegate applies the R6 filter
 *   (`expireAt > now`) before executing the extend.
 * - Sync group: pushes to both `withPushedSync(handle)` and `setCapture(handle)`.
 *
 * ```kotlin
 * val election = MongoLeaderGroupElector(
 *     database.getCollection("bluetape4k_leader_group_locks"),
 *     MongoLeaderGroupElectionOptions(LeaderGroupElectionOptions(maxLeaders = 3))
 * )
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * **Note:** [activeCount] / [availableSlots] are approximate values.
 * Expired documents may linger for up to 60 seconds during a TTL index expiration cycle.
 *
 * @param groupCollection [MongoCollection] storing the group lock state (collection name: [MongoLock.GROUP_LOCK_COLLECTION_NAME])
 * @param options group leader election options
 */
class MongoLeaderGroupElector private constructor(
    private val groupCollection: MongoCollection<Document>,
    val options: MongoLeaderGroupElectionOptions,
    /**
     * Optional history recorder for group slot acquisition lifecycle events.
     */
    private val historyRecorder: SafeLeaderHistoryRecorder? = null,
) : LeaderGroupElector {

    companion object : KLogging() {
        internal const val MONGO_GROUP_FACTORY_BEAN_NAME = "mongo-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(MongoBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            groupCollection: MongoCollection<Document>,
            options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
            historyRecorder: SafeLeaderHistoryRecorder? = null,
        ): MongoLeaderGroupElector {
            MongoLock.ensureIndexes(groupCollection)
            return MongoLeaderGroupElector(groupCollection, options, historyRecorder)
        }
    }

    override val maxLeaders: Int get() = options.maxLeaders

    private fun slotKey(lockName: String, slot: Int) = "$lockName:slot:$slot"

    /**
     * Returns the number of currently active slots (based on non-expired documents).
     *
     * **Note:** This value is approximate. Expired documents may linger for up to 60 seconds
     * during a TTL expiration cycle.
     */
    override fun activeCount(lockName: String): Int {
        val ids = (0 until maxLeaders).map { slotKey(lockName, it) }
        return groupCollection.countDocuments(
            Filters.and(
                Filters.`in`("_id", ids),
                Filters.gt("expireAt", Date())
            )
        ).toInt()
    }

    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        validateMongoLockName(lockName)

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = options.leaderGroupOptions.waitTime / maxLeaders
        val start = Random.nextInt(maxLeaders)

        log.debug { "리더 그룹 슬롯 획득을 요청합니다. lockName=$lockName, maxLeaders=$maxLeaders" }

        for (i in 0 until maxLeaders) {
            val slot = (start + i) % maxLeaders
            val slotKeyValue = slotKey(lockName, slot)
            val lock = MongoLock(groupCollection, slotKeyValue, options.retryDelay)

            if (!lock.tryLock(perSlotWait, leaseTime)) continue

            val startedAt = Instant.now()
            val acquiredAtNanos = System.nanoTime()
            log.debug { "리더 그룹 슬롯을 획득하여 작업을 수행합니다. lockName=$lockName, slot=$slot" }
            val historyKey = recordAcquired(lockName, lock.token, slot, startedAt, leaseTime)

            val delegate = MongoSlotExtendDelegate(lock)
            val identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.GROUP,
                factoryBeanName = MONGO_GROUP_FACTORY_BEAN_NAME,
                groupParams = LockIdentity.GroupParams(maxLeaders),
            )
            val handle = LeaderLockHandle.real(
                identity = identity,
                token = lock.token,
                acquiredAtNanos = acquiredAtNanos,
                slotId = slot.toString(),
                extendDelegate = delegate,
            )
            // Group elector: autoExtend 옵션 부재 — caller 가 LockExtender 로 명시적 연장. watchdog disabled.
            val watchdog = LeaderLeaseAutoExtender.start(false, leaseTime, delegate, ERROR_CLASSIFIER)
            var actionSucceeded = false
            var capturedError: Throwable? = null

            try {
                val result = AopScopeAccess.withPushedSync(handle) {
                    AopScopeAccess.setCapture(handle)
                    try {
                        action()
                    } finally {
                        AopScopeAccess.clearCapture()
                    }
                }
                actionSucceeded = true
                return result
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                capturedError = e
                throw e
            } catch (e: Throwable) {
                capturedError = e
                throw e
            } finally {
                watchdog.close()
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                when {
                    actionSucceeded -> recordCompleted(historyKey, finishedAt, durationMs)
                    capturedError != null -> recordFailed(historyKey, finishedAt, durationMs, capturedError)
                }
                runCatching { lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos) }
                    .onSuccess { log.debug { "리더 그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" } }
                    .onFailure { e -> log.warn(e) { "그룹 슬롯 해제 실패. lockName=$lockName, slot=$slot" } }
            }
        }

        log.debug { "리더 그룹 슬롯 획득 실패 (슬롯 없음). lockName=$lockName" }
        return null
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        validateMongoLockName(lockName)

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = options.leaderGroupOptions.waitTime / maxLeaders
        val start = Random.nextInt(maxLeaders)

        return acquireSlotAsync(lockName, start, perSlotWait, leaseTime).thenComposeAsync({ acquired ->
            if (acquired == null) {
                log.debug { "리더 그룹 슬롯 획득 실패 (비동기). lockName=$lockName" }
                CompletableFuture.completedFuture(null)
            } else {
                val (lock, slot) = acquired
                val acquiredAtNanos = System.nanoTime()
                val startedAt = Instant.now()
                log.debug { "리더 그룹 슬롯을 획득하여 비동기 작업을 수행합니다. lockName=$lockName, slot=$slot" }
                val delegate = MongoSlotExtendDelegate(lock)
                val historyKey = recordAcquired(lockName, lock.token, slot, startedAt, leaseTime)
                // Group elector: watchdog disabled (autoExtend 옵션 부재)
                val watchdog = LeaderLeaseAutoExtender.start(false, leaseTime, delegate, ERROR_CLASSIFIER)
                // async path 는 handle push 미수행 (AOP scope sync/suspend 만 지원)
                val actionFuture = runCatching { action() }
                    .getOrElse { e ->
                        val finishedAt = Instant.now()
                        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                        recordFailed(historyKey, finishedAt, durationMs, e)
                        watchdog.close()
                        runCatching { lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos) }
                            .onFailure { ex -> log.warn(ex) { "그룹 슬롯 해제 실패 (action 오류 경로). lockName=$lockName, slot=$slot" } }
                        return@thenComposeAsync CompletableFuture.failedFuture(e)
                    }
                actionFuture.whenCompleteAsync({ _, throwable ->
                    val finishedAt = Instant.now()
                    val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                    if (throwable == null) {
                        recordCompleted(historyKey, finishedAt, durationMs)
                    } else {
                        recordFailed(historyKey, finishedAt, durationMs, throwable)
                    }
                    watchdog.close()
                    runCatching { lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos) }
                        .onSuccess { log.debug { "비동기 그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" } }
                        .onFailure { e -> log.warn(e) { "비동기 그룹 슬롯 해제 실패. lockName=$lockName, slot=$slot" } }
                }, executor)
            }
        }, executor)
    }

    private fun acquireSlotAsync(
        lockName: String,
        start: Int,
        perSlotWait: Duration,
        leaseTime: Duration,
    ): CompletableFuture<Pair<MongoLock, Int>?> {
        fun attempt(offset: Int): CompletableFuture<Pair<MongoLock, Int>?> {
            if (offset >= maxLeaders) {
                return CompletableFuture.completedFuture(null)
            }

            val slot = (start + offset) % maxLeaders
            val lock = MongoLock(groupCollection, slotKey(lockName, slot), options.retryDelay)
            return lock.tryLockAsync(perSlotWait, leaseTime)
                .thenCompose { acquired ->
                    if (acquired) {
                        CompletableFuture.completedFuture(lock to slot)
                    } else {
                        attempt(offset + 1)
                    }
                }
        }

        return attempt(0)
    }

    private fun recordAcquired(
        lockName: String,
        token: String,
        slot: Int,
        acquiredAt: Instant,
        leaseTime: Duration,
    ): LeaderHistoryKey? {
        val record = historyRecorder?.let {
            LeaderLockHistoryRecord(
                lockName = lockName,
                token = token,
                kind = LockIdentity.AnnotationKind.GROUP,
                acquiredAt = acquiredAt,
                lockedUntil = acquiredAt.plusMillis(leaseTime.inWholeMilliseconds),
                slotId = slot.toString(),
            )
        }
        return record?.let { historyRecorder.recordAcquired(it) }
            ?: record?.let { LeaderHistoryKey(lockName = lockName, token = token, slotId = slot.toString()) }
    }

    private fun recordCompleted(historyKey: LeaderHistoryKey?, finishedAt: Instant, durationMs: Long) =
        historyKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }

    private fun recordFailed(historyKey: LeaderHistoryKey?, finishedAt: Instant, durationMs: Long, error: Throwable?) =
        historyKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, error) }
}

/**
 * Runs [action] only when elected as one of at most [options.maxLeaders] leaders
 * using a MongoDB slot-based distributed semaphore.
 */
fun <T> MongoCollection<Document>.runIfLeaderGroup(
    lockName: String,
    options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
    action: () -> T,
): T? = MongoLeaderGroupElector(this, options).runIfLeader(lockName, action)

/**
 * Runs the async [action] only when elected as one of at most [options.maxLeaders] leaders
 * using a MongoDB slot-based distributed semaphore.
 */
fun <T> MongoCollection<Document>.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = MongoLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)
