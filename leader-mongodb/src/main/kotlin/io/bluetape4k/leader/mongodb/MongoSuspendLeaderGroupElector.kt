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
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
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
 * `MongoSuspendLeaderGroupElector`는 MongoDB backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property groupCollection MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property coroutineGroupCollection MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property historyRecorder MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class MongoSuspendLeaderGroupElector private constructor(
    private val groupCollection: MongoCollection<Document>,
    private val coroutineGroupCollection: CoroutineMongoCollection<Document>,
    val options: MongoLeaderGroupElectionOptions,
    private val historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
) : SuspendLeaderGroupElector, LeaderBackendDiagnosticsProvider by MongoLeaderBackendDiagnostics {

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
     * `activeCount` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
 * `선언` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend fun <T> MongoCollection<Document>.suspendRunIfLeaderGroup(
    coroutineGroupCollection: CoroutineMongoCollection<Document>,
    lockName: String,
    options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? = MongoSuspendLeaderGroupElector(this, coroutineGroupCollection, options).runIfLeader(lockName, action)
