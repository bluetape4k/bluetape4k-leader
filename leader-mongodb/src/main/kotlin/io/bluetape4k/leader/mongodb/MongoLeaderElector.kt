package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.leader.mongodb.lock.validateMongoLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.bson.Document
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * MongoDB 분산 락을 이용한 단일 리더 선출 구현체입니다.
 *
 * `findOneAndUpdate` upsert + TTL index 방식의 토큰 기반 락이므로
 * 스레드에 귀속되지 않으며 Virtual Thread 환경에서 안전합니다.
 *
 * ```kotlin
 * val election = MongoLeaderElector(database.getCollection("bluetape4k_leader_locks"))
 * val result = election.runIfLeader("daily-job") { processData() }
 * // result == processData() 반환값 (리더 획득 성공) 또는 null (획득 실패)
 * ```
 *
 * **WriteConcern:** Replica Set 환경에서는 컬렉션에 `MAJORITY`를 설정하세요.
 * `ACKNOWLEDGED`는 네트워크 분할 시 split-brain 위험이 있습니다.
 *
 * @param collection 락 상태를 저장하는 [MongoCollection] (컬렉션 이름: [MongoLock.LOCK_COLLECTION_NAME])
 * @param options 리더 선출 옵션
 */
class MongoLeaderElector private constructor(
    private val collection: MongoCollection<Document>,
    val options: MongoLeaderElectionOptions,
) : LeaderElector {

    companion object : KLogging() {

        @JvmStatic
        operator fun invoke(
            collection: MongoCollection<Document>,
            options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
        ): MongoLeaderElector {
            MongoLock.ensureIndexes(collection)
            return MongoLeaderElector(collection, options)
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

        val acquiredAtNanos = System.nanoTime()
        val watchdog = LeaderLeaseAutoExtender.start(
            options.leaderOptions.autoExtend,
            options.leaderOptions.leaseTime,
        ) {
            lock.extend(options.leaderOptions.leaseTime)
        }
        log.debug { "리더로 승격하여 작업을 수행합니다. lockName=$lockName" }
        try {
            return action()
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

        return CompletableFuture
            .supplyAsync({ lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime) }, executor)
            .thenComposeAsync({ acquired ->
                if (!acquired) {
                    log.debug { "리더 승격 실패 (슬롯 없음, 비동기). lockName=$lockName" }
                    CompletableFuture.completedFuture(null)
                } else {
                    val acquiredAtNanos = System.nanoTime()
                    val watchdog = LeaderLeaseAutoExtender.start(
                        options.leaderOptions.autoExtend,
                        options.leaderOptions.leaseTime,
                    ) {
                        lock.extend(options.leaderOptions.leaseTime)
                    }
                    log.debug { "리더로 승격하여 비동기 작업을 수행합니다. lockName=$lockName" }
                    val actionFuture = runCatching { action() }
                        .getOrElse { e ->
                            watchdog.close()
                            runCatching { lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos) }
                                .onFailure { ex -> log.warn(ex) { "락 해제 실패 (action 오류 경로). lockName=$lockName" } }
                            return@thenComposeAsync CompletableFuture.failedFuture(e)
                        }
                    actionFuture.whenCompleteAsync({ _, _ ->
                        watchdog.close()
                        runCatching { lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos) }
                            .onSuccess { log.debug { "비동기 리더 권한을 반납했습니다. lockName=$lockName" } }
                            .onFailure { e -> log.warn(e) { "비동기 락 해제 실패. lockName=$lockName" } }
                    }, executor)
                }
            }, executor)
    }
}

/**
 * MongoDB 분산 락을 이용하여 리더로 선출된 경우에만 [action]을 실행합니다.
 */
fun <T> MongoCollection<Document>.runIfLeader(
    lockName: String,
    options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    action: () -> T,
): T? = MongoLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * MongoDB 분산 락을 이용하여 리더로 선출된 경우에만 비동기 [action]을 실행합니다.
 */
fun <T> MongoCollection<Document>.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = MongoLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)
