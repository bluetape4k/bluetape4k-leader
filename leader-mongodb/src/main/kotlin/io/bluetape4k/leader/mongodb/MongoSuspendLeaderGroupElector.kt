package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoCollection as CoroutineMongoCollection
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.mongodb.internal.MongoBackendErrorClassifier
import io.bluetape4k.leader.mongodb.internal.MongoSuspendSlotExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoSuspendLock
import io.bluetape4k.leader.mongodb.lock.validateMongoLockName
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bson.Document
import java.time.Instant
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Coroutine-based multi-leader group election implementation using a MongoDB slot-based distributed semaphore.
 *
 * ## Dual-collection design
 * The `activeCount`, `availableSlots`, and `state` methods of the [LeaderGroupState] interface are non-suspend contracts.
 * Because the coroutine driver's `countDocuments` is a suspend function, it cannot be used for state queries.
 * Therefore, state queries use the synchronous [groupCollection], while lock acquire/release uses
 * the coroutine [coroutineGroupCollection].
 *
 * ## ExtendDelegate Integration (T9 PR 4 / Issue #79)
 *
 * - Wraps each acquired per-slot [MongoSuspendLock] with [MongoSuspendSlotExtendDelegate], sharing the same
 *   reference with the watchdog (AC-15).
 * - The aspect's `LockExtenderSuspend.extendActiveLockSuspend` uses the same delegate reference.
 * - Propagates the handle into the coroutine context via
 *   `withContext(createLockHandleElement(handle))`.
 *
 * ```kotlin
 * val db = mongoClient.getDatabase("mydb")
 * val election = MongoSuspendLeaderGroupElector(
 *     groupCollection = db.getCollection("bluetape4k_leader_group_locks"),
 *     coroutineGroupCollection = db.toCoroutineDatabase().getCollection("bluetape4k_leader_group_locks"),
 *     options = MongoLeaderGroupElectionOptions(LeaderGroupElectionOptions(maxLeaders = 3)),
 * )
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * @param groupCollection synchronous [MongoCollection] for state queries
 * @param coroutineGroupCollection coroutine [CoroutineMongoCollection] for lock acquire/release
 * @param options group leader election options
 */
class MongoSuspendLeaderGroupElector private constructor(
    private val groupCollection: MongoCollection<Document>,
    private val coroutineGroupCollection: CoroutineMongoCollection<Document>,
    val options: MongoLeaderGroupElectionOptions,
    private val historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
) : SuspendLeaderGroupElector {

    init {
        check(groupCollection.namespace.fullName == coroutineGroupCollection.namespace.fullName) {
            "groupCollection과 coroutineGroupCollection은 동일한 namespace여야 합니다: " +
                "${groupCollection.namespace.fullName} vs ${coroutineGroupCollection.namespace.fullName}"
        }
    }

    companion object : KLoggingChannel() {
        internal const val MONGO_SUSPEND_GROUP_FACTORY_BEAN_NAME = "mongo-suspend-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(MongoBackendErrorClassifier)

        suspend operator fun invoke(
            groupCollection: MongoCollection<Document>,
            coroutineGroupCollection: CoroutineMongoCollection<Document>,
            options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
            historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
        ): MongoSuspendLeaderGroupElector {
            // 두 컬렉션은 동일 namespace이므로 coroutine 드라이버에서 한 번만 인덱스 생성하면 충분
            MongoSuspendLock.ensureIndexes(coroutineGroupCollection)
            return MongoSuspendLeaderGroupElector(groupCollection, coroutineGroupCollection, options, historyRecorder)
        }
    }

    override val maxLeaders: Int get() = options.maxLeaders

    private fun slotKey(lockName: String, slot: Int) = "$lockName:slot:$slot"

    /**
     * Returns the number of currently active slots using the synchronous driver
     * (based on non-expired documents).
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

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        validateMongoLockName(lockName)

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = options.leaderGroupOptions.waitTime / maxLeaders
        val start = Random.nextInt(maxLeaders)

        log.debug { "리더 그룹 슬롯 획득을 요청합니다 (suspend). lockName=$lockName, maxLeaders=$maxLeaders" }

        var acquiredLock: MongoSuspendLock? = null
        var acquiredSlot = -1
        var acquiredSlotKey: String? = null

        for (i in 0 until maxLeaders) {
            currentCoroutineContext().ensureActive()
            val slot = (start + i) % maxLeaders
            val slotKeyValue = slotKey(lockName, slot)
            val lock = MongoSuspendLock(coroutineGroupCollection, slotKeyValue, options.retryDelay)

            if (lock.tryLock(perSlotWait, leaseTime)) {
                acquiredLock = lock
                acquiredSlot = slot
                acquiredSlotKey = slotKeyValue
                break
            }
        }

        if (acquiredLock == null || acquiredSlotKey == null) {
            log.debug { "리더 그룹 슬롯 획득 실패 (슬롯 없음, suspend). lockName=$lockName" }
            return null
        }

        log.debug { "리더 그룹 슬롯을 획득하여 suspend 작업을 수행합니다. lockName=$lockName, slot=$acquiredSlot" }
        val lock = acquiredLock
        val slot = acquiredSlot
        val startedAt = Instant.now()
        val acquiredAtNanos = System.nanoTime()
        val historyKey = recordAcquired(lockName, lock.token, slot, startedAt, leaseTime)

        val delegate: SuspendExtendDelegate = MongoSuspendSlotExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = MONGO_SUSPEND_GROUP_FACTORY_BEAN_NAME,
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
            val result = withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
            actionSucceeded = true
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            capturedError = e
            throw e
        } finally {
            // NonCancellable: 코루틴 취소 시에도 watchdog close + release 가 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog.close()
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                when {
                    actionSucceeded -> recordCompleted(historyKey, finishedAt, durationMs)
                    capturedError != null -> recordFailed(historyKey, finishedAt, durationMs, capturedError)
                }
                try {
                    lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos)
                    log.debug { "리더 그룹 슬롯을 반납했습니다 (suspend). lockName=$lockName, slot=$slot" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "그룹 슬롯 해제 실패 (suspend). lockName=$lockName, slot=$slot" }
                }
            }
        }
    }

    private suspend fun recordAcquired(
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

    private suspend fun recordCompleted(historyKey: LeaderHistoryKey?, finishedAt: Instant, durationMs: Long) =
        historyKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }

    private suspend fun recordFailed(historyKey: LeaderHistoryKey?, finishedAt: Instant, durationMs: Long, error: Throwable?) =
        historyKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, error) }
}

/**
 * Runs the suspend [action] only when elected as one of at most [options.maxLeaders] leaders
 * using a MongoDB slot-based distributed semaphore.
 *
 * The receiver is the synchronous collection for state queries; [coroutineGroupCollection] must also be
 * provided for lock acquire/release. This is required by the dual-collection design of
 * [MongoSuspendLeaderGroupElector].
 */
suspend fun <T> MongoCollection<Document>.suspendRunIfLeaderGroup(
    coroutineGroupCollection: CoroutineMongoCollection<Document>,
    lockName: String,
    options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? = MongoSuspendLeaderGroupElector(this, coroutineGroupCollection, options).runIfLeader(lockName, action)
