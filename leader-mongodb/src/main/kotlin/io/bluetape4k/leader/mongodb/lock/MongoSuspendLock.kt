package io.bluetape4k.leader.mongodb.lock

import com.mongodb.MongoCommandException
import com.mongodb.MongoException
import com.mongodb.MongoSecurityException
import com.mongodb.MongoTimeoutException
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.bson.Document
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * MongoDB `findOneAndUpdate` upsert + TTL index 기반 코루틴 토큰 분산 락입니다.
 *
 * [MongoLock]과 동일한 락 전략을 사용하되, `suspend` 함수로 제공합니다.
 * 블로킹 `Thread.sleep` 대신 `delay()`를 사용하여 코루틴 스레드를 점유하지 않습니다.
 *
 * ## 취소 안전성
 * `unlock()`은 코루틴 취소에 취약합니다.
 * **반드시 `withContext(NonCancellable)` 블록 안에서 호출해야 합니다.**
 * 취소된 컨텍스트에서 직접 호출하면 CancellationException으로 즉시 중단됩니다.
 * Election 구현체 (예: [MongoSuspendLeaderElector])가 이 보장을 담당합니다.
 *
 * ## 설계 주의사항
 * - [leaseTime]은 action의 최대 실행 시간보다 충분히 커야 합니다.
 * - Replica Set 환경에서는 `WriteConcern.MAJORITY` 권장.
 * - 토큰 기반이므로 코루틴 스레드 전환과 무관하게 안전합니다.
 *
 * @param collection 락 상태를 저장하는 코루틴 [MongoCollection]
 * @param lockKey 락 식별 키
 * @param retryDelay 재시도 대기 기본 시간 (jitter 포함)
 */
class MongoSuspendLock private constructor(
    private val collection: MongoCollection<Document>,
    val lockKey: String,
    private val retryDelay: Duration,
) {
    companion object : KLoggingChannel() {
        private val ensuredNamespaces: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * 컬렉션에 TTL 인덱스 (`expireAt`, expireAfterSeconds=0)를 생성합니다.
         *
         * 네임스페이스 당 최초 1회만 실행됩니다. 생성 실패 시 네임스페이스를
         * guard set에서 제거하여 다음 호출 시 재시도할 수 있게 합니다.
         */
        suspend fun ensureIndexes(collection: MongoCollection<Document>) {
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

        /** 테스트에서 특정 네임스페이스의 ensureIndexes 상태를 초기화합니다. */
        internal fun resetEnsuredFor(namespace: String) {
            ensuredNamespaces.remove(namespace)
        }

        suspend operator fun invoke(
            collection: MongoCollection<Document>,
            lockKey: String,
            retryDelay: Duration = 50.milliseconds,
        ): MongoSuspendLock {
            ensureIndexes(collection)
            return MongoSuspendLock(collection, lockKey, retryDelay)
        }
    }

    private val token: String = Base58.randomString(8)

    /**
     * [waitTime] 내에 락 획득을 시도합니다. 성공 시 `true`, 타임아웃 또는 오류 시 `false`를 반환합니다.
     *
     * 이 함수는 절대 예외를 throw하지 않습니다 (단, `CancellationException`은 전파됩니다).
     * 코루틴 취소 시 `ensureActive()`에서 `CancellationException`이 발생하여 루프를 탈출합니다.
     *
     * @param waitTime 락 획득 최대 대기 시간
     * @param leaseTime 락 보유(TTL) 최대 시간
     * @return 락 획득 성공 시 `true`, 실패 또는 오류 시 `false`
     */
    suspend fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds

        do {
            currentCoroutineContext().ensureActive()

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
                when (e.errorCode) {
                    11000 -> null  // Duplicate Key — 유효한 락이 이미 존재, 재시도
                    13, 18 -> {
                        log.error(e) { "MongoDB 인증 오류 (code=${e.errorCode}) 발생: lockKey=$lockKey" }
                        return false
                    }
                    else -> {
                        log.warn(e) { "MongoDB 커맨드 오류 (code=${e.errorCode}) 발생: lockKey=$lockKey" }
                        return false
                    }
                }
            } catch (e: MongoWriteException) {
                if (e.code == 11000) null  // Duplicate Key — 재시도
                else {
                    log.warn(e) { "MongoDB 쓰기 오류 (code=${e.code}) 발생: lockKey=$lockKey" }
                    return false
                }
            } catch (e: MongoTimeoutException) {
                log.warn(e) { "MongoDB 타임아웃 발생: lockKey=$lockKey" }
                return false
            } catch (e: MongoSecurityException) {
                log.error(e) { "MongoDB 보안 오류 발생: lockKey=$lockKey" }
                return false
            } catch (e: MongoException) {
                log.warn(e) { "MongoDB 오류 발생: lockKey=$lockKey" }
                return false
            }

            if (result?.getString("token") == token) {
                log.debug { "락 획득 성공 (suspend): lockKey=$lockKey" }
                return true
            }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0L) {
                // AWS full jitter: delay ∈ [1ms, retryDelay) — 동일 retry 윈도우에 인스턴스가 몰리는 것을 방지
                val jitter = Random.nextLong(1, retryDelay.inWholeMilliseconds.coerceAtLeast(2))
                delay(minOf(jitter, remaining).milliseconds)
            }
        } while (System.currentTimeMillis() < deadline)

        log.debug { "락 획득 실패 (타임아웃, suspend): lockKey=$lockKey" }
        return false
    }

    /**
     * 현재 인스턴스(토큰)가 락을 보유하고 있는지 확인합니다.
     */
    suspend fun isHeldByCurrentInstance(): Boolean =
        collection.countDocuments(
            Filters.and(Filters.eq("_id", lockKey), Filters.eq("token", token))
        ) > 0

    /**
     * 현재 인스턴스가 보유한 락을 해제합니다.
     *
     * **반드시 `withContext(NonCancellable)` 블록 안에서 호출해야 합니다.**
     * 취소된 컨텍스트에서 직접 호출하면 CancellationException이 즉시 발생합니다.
     *
     * 토큰 불일치 시 경고 로그만 남깁니다.
     */
    suspend fun unlock(
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
            log.warn { "락 해제 실패 — 토큰 불일치 또는 이미 만료됨 (suspend): lockKey=$lockKey" }
        } else {
            log.debug { "락 해제 성공 (suspend): lockKey=$lockKey" }
        }
    }

    suspend fun extend(leaseTime: Duration): Boolean {
        val matched = collection.updateOne(
            Filters.and(Filters.eq("_id", lockKey), Filters.eq("token", token)),
            Updates.set("expireAt", Date(System.currentTimeMillis() + leaseTime.inWholeMilliseconds))
        ).matchedCount

        return matched > 0L
    }
}
