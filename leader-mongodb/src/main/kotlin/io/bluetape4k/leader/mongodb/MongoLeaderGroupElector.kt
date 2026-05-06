package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.leader.mongodb.lock.validateMongoLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.bson.Document
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.random.Random

/**
 * MongoDB 슬롯 기반 분산 세마포어를 이용한 복수 리더 그룹 선출 구현체입니다.
 *
 * `${lockName}:slot:N` 키를 사용하는 [MongoLock] N개로 `maxLeaders` 슬롯을 시뮬레이션합니다.
 * 슬롯 시작 위치를 랜덤화하여 핫스팟을 방지합니다.
 *
 * ```kotlin
 * val election = MongoLeaderGroupElector(
 *     database.getCollection("bluetape4k_leader_group_locks"),
 *     MongoLeaderGroupElectionOptions(LeaderGroupElectionOptions(maxLeaders = 3))
 * )
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * **주의:** [activeCount] / [availableSlots]는 근사치입니다.
 * TTL 인덱스 만료 주기(최대 60초) 동안 만료된 문서가 잔류할 수 있습니다.
 *
 * @param groupCollection 그룹 락 상태를 저장하는 [MongoCollection] (컬렉션 이름: [MongoLock.GROUP_LOCK_COLLECTION_NAME])
 * @param options 그룹 리더 선출 옵션
 */
class MongoLeaderGroupElector private constructor(
    private val groupCollection: MongoCollection<Document>,
    val options: MongoLeaderGroupElectionOptions,
) : LeaderGroupElector {

    companion object : KLogging() {

        @JvmStatic
        operator fun invoke(
            groupCollection: MongoCollection<Document>,
            options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
        ): MongoLeaderGroupElector {
            MongoLock.ensureIndexes(groupCollection)
            return MongoLeaderGroupElector(groupCollection, options)
        }
    }

    override val maxLeaders: Int get() = options.maxLeaders

    private fun slotKey(lockName: String, slot: Int) = "$lockName:slot:$slot"

    /**
     * 현재 활성 슬롯 수를 반환합니다 (만료되지 않은 문서 기준).
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

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        validateMongoLockName(lockName)

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = options.leaderGroupOptions.waitTime / maxLeaders
        val start = Random.nextInt(maxLeaders)

        log.debug { "리더 그룹 슬롯 획득을 요청합니다. lockName=$lockName, maxLeaders=$maxLeaders" }

        for (i in 0 until maxLeaders) {
            val slot = (start + i) % maxLeaders
            val lock = MongoLock(groupCollection, slotKey(lockName, slot), options.retryDelay)

            if (!lock.tryLock(perSlotWait, leaseTime)) continue

            log.debug { "리더 그룹 슬롯을 획득하여 작업을 수행합니다. lockName=$lockName, slot=$slot" }
            try {
                return action()
            } finally {
                runCatching { lock.unlock() }
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

        return CompletableFuture.supplyAsync({
            (0 until maxLeaders)
                .asSequence()
                .map { i ->
                    val slot = (start + i) % maxLeaders
                    MongoLock(groupCollection, slotKey(lockName, slot), options.retryDelay) to slot
                }
                .firstOrNull { (lock, _) -> lock.tryLock(perSlotWait, leaseTime) }
        }, executor).thenComposeAsync({ acquired ->
            if (acquired == null) {
                log.debug { "리더 그룹 슬롯 획득 실패 (비동기). lockName=$lockName" }
                CompletableFuture.completedFuture(null)
            } else {
                val (lock, slot) = acquired
                log.debug { "리더 그룹 슬롯을 획득하여 비동기 작업을 수행합니다. lockName=$lockName, slot=$slot" }
                val actionFuture = runCatching { action() }
                    .getOrElse { e ->
                        runCatching { lock.unlock() }
                            .onFailure { ex -> log.warn(ex) { "그룹 슬롯 해제 실패 (action 오류 경로). lockName=$lockName, slot=$slot" } }
                        return@thenComposeAsync CompletableFuture.failedFuture(e)
                    }
                actionFuture.whenCompleteAsync({ _, _ ->
                    runCatching { lock.unlock() }
                        .onSuccess { log.debug { "비동기 그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" } }
                        .onFailure { e -> log.warn(e) { "비동기 그룹 슬롯 해제 실패. lockName=$lockName, slot=$slot" } }
                }, executor)
            }
        }, executor)
    }
}

/**
 * MongoDB 분산 세마포어(슬롯 기반)를 이용하여 최대 [options.maxLeaders]개의 리더로 선출된 경우에만 [action]을 실행합니다.
 */
fun <T> MongoCollection<Document>.runIfLeaderGroup(
    lockName: String,
    options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
    action: () -> T,
): T? = MongoLeaderGroupElector(this, options).runIfLeader(lockName, action)

/**
 * MongoDB 분산 세마포어(슬롯 기반)를 이용하여 최대 [options.maxLeaders]개의 리더로 선출된 경우에만 비동기 [action]을 실행합니다.
 */
fun <T> MongoCollection<Document>.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = MongoLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)
