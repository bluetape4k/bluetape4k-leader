package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoCollection as CoroutineMongoCollection
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElection
import io.bluetape4k.leader.mongodb.lock.MongoSuspendLock
import io.bluetape4k.leader.mongodb.lock.validateLockName
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
 * ```kotlin
 * val db = mongoClient.getDatabase("mydb")
 * val election = MongoSuspendLeaderGroupElection(
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
class MongoSuspendLeaderGroupElection private constructor(
    private val groupCollection: MongoCollection<Document>,
    private val coroutineGroupCollection: CoroutineMongoCollection<Document>,
    val options: MongoLeaderGroupElectionOptions,
) : SuspendLeaderGroupElection {

    init {
        require(groupCollection.namespace.fullName == coroutineGroupCollection.namespace.fullName) {
            "groupCollection과 coroutineGroupCollection은 동일한 namespace여야 합니다: " +
                "${groupCollection.namespace.fullName} vs ${coroutineGroupCollection.namespace.fullName}"
        }
    }

    companion object : KLoggingChannel() {

        suspend operator fun invoke(
            groupCollection: MongoCollection<Document>,
            coroutineGroupCollection: CoroutineMongoCollection<Document>,
            options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
        ): MongoSuspendLeaderGroupElection {
            // 두 컬렉션은 동일 namespace이므로 coroutine 드라이버에서 한 번만 인덱스 생성하면 충분
            MongoSuspendLock.ensureIndexes(coroutineGroupCollection)
            return MongoSuspendLeaderGroupElection(groupCollection, coroutineGroupCollection, options)
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
        validateLockName(lockName)

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = options.leaderGroupOptions.waitTime.dividedBy(maxLeaders.toLong())
        val start = Random.nextInt(maxLeaders)

        log.debug { "리더 그룹 슬롯 획득을 요청합니다 (suspend). lockName=$lockName, maxLeaders=$maxLeaders" }

        var acquiredLock: MongoSuspendLock? = null
        var acquiredSlot = -1

        for (i in 0 until maxLeaders) {
            currentCoroutineContext().ensureActive()
            val slot = (start + i) % maxLeaders
            val lock = MongoSuspendLock(coroutineGroupCollection, slotKey(lockName, slot), options.retryDelay)

            if (lock.tryLock(perSlotWait, leaseTime)) {
                acquiredLock = lock
                acquiredSlot = slot
                break
            }
        }

        if (acquiredLock == null) {
            log.debug { "리더 그룹 슬롯 획득 실패 (슬롯 없음, suspend). lockName=$lockName" }
            return null
        }

        log.debug { "리더 그룹 슬롯을 획득하여 suspend 작업을 수행합니다. lockName=$lockName, slot=$acquiredSlot" }
        val lock = acquiredLock
        val slot = acquiredSlot
        try {
            return action()
        } finally {
            withContext(NonCancellable) {
                try {
                    lock.unlock()
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
 * 이는 [MongoSuspendLeaderGroupElection]의 이중 컬렉션 설계 때문입니다.
 */
suspend fun <T> MongoCollection<Document>.suspendRunIfLeaderGroup(
    coroutineGroupCollection: CoroutineMongoCollection<Document>,
    lockName: String,
    options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? = MongoSuspendLeaderGroupElection(this, coroutineGroupCollection, options).runIfLeader(lockName, action)
