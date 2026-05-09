package io.bluetape4k.leader.mongodb

import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.mongodb.lock.MongoSuspendLock
import io.bluetape4k.leader.mongodb.lock.validateMongoLockName
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.bson.Document

/**
 * MongoDB 분산 락을 이용한 코루틴 기반 단일 리더 선출 구현체입니다.
 *
 * 토큰 기반 락 (`findOneAndUpdate` + TTL)으로 코루틴 스레드 전환과 무관하게 안전합니다.
 *
 * ```kotlin
 * val election = MongoSuspendLeaderElector(coroutineDatabase.getCollection("bluetape4k_leader_locks"))
 * val result = election.runIfLeader("daily-job") {
 *     delay(100)
 *     processData()
 * }
 * ```
 *
 * **취소 안전성:** 코루틴 취소 시에도 `withContext(NonCancellable)`로 락 해제를 보장합니다.
 *
 * @param collection 락 상태를 저장하는 코루틴 [MongoCollection]
 * @param options 리더 선출 옵션
 */
class MongoSuspendLeaderElector private constructor(
    private val collection: MongoCollection<Document>,
    val options: MongoLeaderElectionOptions,
) : SuspendLeaderElector {

    companion object : KLoggingChannel() {

        suspend operator fun invoke(
            collection: MongoCollection<Document>,
            options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
        ): MongoSuspendLeaderElector {
            MongoSuspendLock.ensureIndexes(collection)
            return MongoSuspendLeaderElector(collection, options)
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

        val acquiredAtNanos = System.nanoTime()
        log.debug { "리더로 승격하여 suspend 작업을 수행합니다. lockName=$lockName" }
        try {
            return action()
        } finally {
            withContext(NonCancellable) {
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
 * MongoDB 분산 락을 이용하여 리더로 선출된 경우에만 suspend [action]을 실행합니다.
 */
suspend fun <T> MongoCollection<Document>.suspendRunIfLeader(
    lockName: String,
    options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? = MongoSuspendLeaderElector(this, options).runIfLeader(lockName, action)
