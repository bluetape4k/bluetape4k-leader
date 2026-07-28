package io.bluetape4k.leader.mongodb.lock

import com.mongodb.MongoCommandException
import com.mongodb.MongoException
import com.mongodb.MongoSecurityException
import com.mongodb.MongoTimeoutException
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.leader.mongodb.internal.MonotonicDeadline
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import org.bson.Document
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `MongoLock`는 MongoDB backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property collection MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockKey MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property retryDelay MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class MongoLock private constructor(
    private val collection: MongoCollection<Document>,
    val lockKey: String,
    private val retryDelay: Duration,
) {
    companion object : KLogging() {
        /**
         * `LOCK_COLLECTION_NAME` 값은 MongoDB backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        const val LOCK_COLLECTION_NAME = "bluetape4k_leader_locks"

        /**
         * `GROUP_LOCK_COLLECTION_NAME` 값은 MongoDB backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        const val GROUP_LOCK_COLLECTION_NAME = "bluetape4k_leader_group_locks"

        private val ensuredNamespaces: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * `ensureIndexes` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
         *
         * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
         */
        fun ensureIndexes(collection: MongoCollection<Document>) {
            val namespace = collection.namespace.fullName
            if (ensuredNamespaces.add(namespace)) {
                try {
                    collection.createIndex(
                        Indexes.ascending("expireAt"),
                        IndexOptions().expireAfter(0L, TimeUnit.SECONDS)
                    )
                } catch (e: Exception) {
                    ensuredNamespaces.remove(namespace)
                    throw e
                }
            }
        }

        /**
         * `resetEnsuredFor` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
         *
         * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
         */
        internal fun resetEnsuredFor(namespace: String) {
            ensuredNamespaces.remove(namespace)
        }

        operator fun invoke(
            collection: MongoCollection<Document>,
            lockKey: String,
            retryDelay: Duration = 50.milliseconds,
        ): MongoLock {
            ensureIndexes(collection)
            return MongoLock(collection, lockKey, retryDelay)
        }
    }

    internal val token: String = Base58.randomString(22)

    private enum class AcquireResult {
        ACQUIRED,
        CONTENDED,
        FAILED,
    }

    /**
     * `tryLock` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = MonotonicDeadline.fromNow(waitTime)

        do {
            when (tryAcquireOnce(leaseTime)) {
                AcquireResult.ACQUIRED -> {
                    log.debug { "락 획득 성공: lockKey=$lockKey" }
                    return true
                }
                AcquireResult.CONTENDED -> Unit
                AcquireResult.FAILED -> return false
            }

            if (deadline.hasTimeRemaining()) {
                // AWS full jitter: sleep ∈ [1ms, retryDelay) — 동일 retry 윈도우에 인스턴스가 몰리는 것을 방지
                val jitter = Random.nextLong(1, retryDelay.inWholeMilliseconds.coerceAtLeast(2))
                val delayMillis = deadline.remainingMillisForDelay(jitter)
                if (delayMillis > 0L) {
                    Thread.sleep(delayMillis)
                }
            }
        } while (deadline.hasTimeRemaining())

        log.debug { "락 획득 실패 (타임아웃): lockKey=$lockKey" }
        return false
    }

    /**
     * `tryLockAsync` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun tryLockAsync(
        waitTime: Duration,
        leaseTime: Duration,
        executor: Executor = VirtualThreadExecutor,
    ): CompletableFuture<Boolean> {
        val deadline = MonotonicDeadline.fromNow(waitTime)

        fun attempt(): CompletableFuture<Boolean> {
            return CompletableFuture.supplyAsync({ tryAcquireOnce(leaseTime) }, executor)
                .thenCompose { result ->
                    when (result) {
                        AcquireResult.ACQUIRED -> {
                            log.debug { "락 획득 성공 (async): lockKey=$lockKey" }
                            CompletableFuture.completedFuture(true)
                        }
                        AcquireResult.FAILED -> CompletableFuture.completedFuture(false)
                        AcquireResult.CONTENDED -> {
                            if (!deadline.hasTimeRemaining()) {
                                log.debug { "락 획득 실패 (타임아웃, async): lockKey=$lockKey" }
                                CompletableFuture.completedFuture(false)
                            } else {
                                val jitter = Random.nextLong(1, retryDelay.inWholeMilliseconds.coerceAtLeast(2))
                                val delayMillis = deadline.remainingMillisForDelay(jitter)
                                val delayed = CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS)
                                CompletableFuture.runAsync({}, delayed).thenCompose { attempt() }
                            }
                        }
                    }
                }
        }

        return attempt()
    }

    private fun tryAcquireOnce(leaseTime: Duration): AcquireResult {
        val result: Document? = try {
            collection.findOneAndUpdate(
                Filters.and(
                    Filters.eq("_id", lockKey),
                    Filters.lt("expireAt", Date())
                ),
                Updates.combine(
                    Updates.set("token", token),
                    Updates.set("expireAt", Date(System.currentTimeMillis() + leaseTime.inWholeMilliseconds))
                ),
                FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
            )
        } catch (e: MongoCommandException) {
            return when (e.errorCode) {
                11000 -> AcquireResult.CONTENDED  // Duplicate Key — 유효한 락이 이미 존재, 재시도
                13, 18 -> {
                    log.error(e) { "MongoDB 인증 오류 (code=${e.errorCode}) 발생: lockKey=$lockKey" }
                    AcquireResult.FAILED
                }
                else -> {
                    log.warn(e) { "MongoDB 커맨드 오류 (code=${e.errorCode}) 발생: lockKey=$lockKey" }
                    AcquireResult.FAILED
                }
            }
        } catch (e: MongoWriteException) {
            return if (e.code == 11000) {
                AcquireResult.CONTENDED  // Duplicate Key — 재시도
            } else {
                log.warn(e) { "MongoDB 쓰기 오류 (code=${e.code}) 발생: lockKey=$lockKey" }
                AcquireResult.FAILED
            }
        } catch (e: MongoTimeoutException) {
            log.warn(e) { "MongoDB 타임아웃 발생: lockKey=$lockKey" }
            return AcquireResult.FAILED
        } catch (e: MongoSecurityException) {
            log.error(e) { "MongoDB 보안 오류 발생: lockKey=$lockKey" }
            return AcquireResult.FAILED
        } catch (e: MongoException) {
            log.warn(e) { "MongoDB 오류 발생: lockKey=$lockKey" }
            return AcquireResult.FAILED
        }

        return if (result?.getString("token") == token) {
            AcquireResult.ACQUIRED
        } else {
            AcquireResult.CONTENDED
        }
    }

    /**
     * `isHeldByCurrentInstance` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun isHeldByCurrentInstance(): Boolean =
        collection.find(
            Filters.and(Filters.eq("_id", lockKey), Filters.eq("token", token))
        ).first() != null

    /**
     * `unlock` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) {
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)
        val matched = if (remaining > Duration.ZERO) {
            collection.updateOne(
                Filters.and(Filters.eq("_id", lockKey), Filters.eq("token", token)),
                Updates.set("expireAt", Date(System.currentTimeMillis() + remaining.inWholeMilliseconds))
            ).matchedCount
        } else {
            collection.deleteOne(
                Filters.and(Filters.eq("_id", lockKey), Filters.eq("token", token))
            ).deletedCount
        }
        if (matched == 0L) {
            log.warn { "락 해제 실패 — 토큰 불일치 또는 이미 만료됨: lockKey=$lockKey" }
        } else {
            log.debug { "락 해제 성공: lockKey=$lockKey" }
        }
    }

    /**
     * `extendDetailed` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun extendDetailed(leaseTime: Duration): ExtendOutcome {
        val nowMs = System.currentTimeMillis()
        val leaseMs = leaseTime.inWholeMilliseconds
        val newExpireAt = Date(nowMs + leaseMs)
        val matched = collection.updateOne(
            Filters.and(
                Filters.eq("_id", lockKey),
                Filters.eq("token", token),
                Filters.gt("expireAt", Date(nowMs)),  // R6: expired-doc revival 차단
            ),
            Updates.set("expireAt", newExpireAt),
        ).matchedCount

        return if (matched > 0L) {
            ExtendOutcome.Extended(Instant.ofEpochMilli(nowMs + leaseMs))
        } else {
            log.debug { "MongoDB extend 실패 (NotHeld): lockKey=$lockKey" }
            ExtendOutcome.NotHeld
        }
    }
}

internal fun validateMongoLockName(lockName: String) {
    io.bluetape4k.leader.validateLockName(lockName)
    require(!lockName.contains(":slot:")) { "lockName must not contain ':slot:': $lockName" }
}
