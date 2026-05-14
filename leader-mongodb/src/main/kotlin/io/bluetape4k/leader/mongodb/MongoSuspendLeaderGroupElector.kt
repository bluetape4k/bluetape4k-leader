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
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
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
import java.util.Date
import kotlin.random.Random

/**
 * MongoDB 슬롯 기반 분산 세마포어를 이용한 코루틴 기반 복수 리더 그룹 선출 구현체입니다.
 *
 * ## 이중 컬렉션 설계
 * [LeaderGroupState] 인터페이스의 `activeCount`, `availableSlots`, `state`는 non-suspend 계약입니다.
 * 코루틴 드라이버의 `countDocuments`는 suspend 함수이므로 state 조회에 사용할 수 없습니다.
 * 따라서 state 조회는 동기 [groupCollection]으로, 락 획득/해제는 코루틴 [coroutineGroupCollection]으로 처리합니다.
 *
 * ## ExtendDelegate 통합 (T9 PR 4 / Issue #79)
 *
 * - acquire 된 per-slot [MongoSuspendLock] 을 [MongoSuspendSlotExtendDelegate] 로 wrap 하여 watchdog 와 동일 reference 공유 (AC-15).
 * - aspect 의 `LockExtenderSuspend.extendActiveLockSuspend` 는 동일 delegate reference 를 사용합니다.
 * - suspend group: `withContext(createLockHandleElement(handle))` 로 coroutineContext 에 handle 전파.
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
 * @param groupCollection state 조회용 동기 [MongoCollection]
 * @param coroutineGroupCollection 락 획득/해제용 코루틴 [CoroutineMongoCollection]
 * @param options 그룹 리더 선출 옵션
 */
class MongoSuspendLeaderGroupElector private constructor(
    private val groupCollection: MongoCollection<Document>,
    private val coroutineGroupCollection: CoroutineMongoCollection<Document>,
    val options: MongoLeaderGroupElectionOptions,
    @Suppress("unused") private val historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
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
     * 현재 활성 슬롯 수를 반환합니다 (만료되지 않은 문서 기준, 동기 드라이버 사용).
     *
     * **주의:** 이 값은 근사치입니다. TTL 만료 주기(최대 60초) 동안 만료 문서가 잔류할 수 있습니다.
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
        val slotKeyValue = acquiredSlotKey
        val acquiredAtNanos = System.nanoTime()

        val delegate = MongoSuspendSlotExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = MONGO_SUSPEND_GROUP_FACTORY_BEAN_NAME,
            groupParams = LockIdentity.GroupParams(maxLeaders),
        )
        val handle = LeaderLockHandle.real(
            identity = identity,
            token = slotKeyValue,
            acquiredAtNanos = acquiredAtNanos,
            slotId = slot.toString(),
            extendDelegate = delegate,
        )
        // Group elector: autoExtend 옵션 부재 — caller 가 LockExtender 로 명시적 연장. watchdog disabled.
        val watchdog = LeaderLeaseAutoExtender.start(false, leaseTime, delegate, ERROR_CLASSIFIER)

        try {
            return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
        } finally {
            // NonCancellable: 코루틴 취소 시에도 watchdog close + release 가 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog.close()
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
}

/**
 * MongoDB 분산 세마포어(슬롯 기반)를 이용하여 최대 [options.maxLeaders]개의 리더로 선출된 경우에만 suspend [action]을 실행합니다.
 *
 * 수신자는 state 조회용 동기 컬렉션이고, 락 획득/해제용 [coroutineGroupCollection]을 함께 전달해야 합니다.
 * 이는 [MongoSuspendLeaderGroupElector]의 이중 컬렉션 설계 때문입니다.
 */
suspend fun <T> MongoCollection<Document>.suspendRunIfLeaderGroup(
    coroutineGroupCollection: CoroutineMongoCollection<Document>,
    lockName: String,
    options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? = MongoSuspendLeaderGroupElector(this, coroutineGroupCollection, options).runIfLeader(lockName, action)
