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
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import org.bson.Document
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * MongoDB `findOneAndUpdate` upsert + TTL index 기반 토큰 분산 락입니다.
 *
 * ## 동작 방식
 * - `findOneAndUpdate(filter=expired, upsert=true)`: 만료된 락 문서를 갱신하거나 새로 삽입하여 원자적으로 획득
 * - E11000 Duplicate Key: 유효한 락이 이미 존재 → `null` 반환 후 재시도
 * - TTL index (`expireAt`, expireAfterSeconds=0): 만료 문서를 MongoDB가 자동으로 정리
 *
 * ## 설계 주의사항
 * - [leaseTime]은 action의 최대 실행 시간보다 충분히 커야 합니다.
 *   TTL 만료 후 다른 인스턴스가 락을 재획득할 수 있습니다(takeover 위험).
 * - Replica Set 환경에서는 `WriteConcern.MAJORITY` 권장.
 *   `ACKNOWLEDGED`는 네트워크 분할 시 split-brain 위험이 있습니다.
 * - 토큰 기반이므로 스레드에 귀속되지 않으며 Virtual Thread 환경에서 안전합니다.
 *
 * @param collection 락 상태를 저장하는 [MongoCollection]
 * @param lockKey 락 식별 키
 * @param retryDelay 재시도 대기 기본 시간 (jitter 포함)
 */
class MongoLock private constructor(
    private val collection: MongoCollection<Document>,
    val lockKey: String,
    private val retryDelay: Duration,
) {
    companion object : KLogging() {
        /** 단일 리더 선출용 컬렉션 이름 */
        const val LOCK_COLLECTION_NAME = "bluetape4k_leader_locks"

        /** 그룹 리더 선출용 컬렉션 이름 */
        const val GROUP_LOCK_COLLECTION_NAME = "bluetape4k_leader_group_locks"

        private val ensuredNamespaces: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * 컬렉션에 TTL 인덱스 (`expireAt`, expireAfterSeconds=0)를 생성합니다.
         *
         * 네임스페이스 당 최초 1회만 실행됩니다. 인덱스 생성 실패 시 네임스페이스를
         * guard set에서 제거하여 다음 호출 시 재시도할 수 있게 합니다.
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

        /** 테스트에서 특정 네임스페이스의 ensureIndexes 상태를 초기화합니다. */
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

    internal val token: String = Base58.randomString(8)

    /**
     * [waitTime] 내에 락 획득을 시도합니다. 성공 시 `true`, 타임아웃 또는 오류 시 `false`를 반환합니다.
     *
     * 이 함수는 절대 예외를 throw하지 않습니다. MongoDB 예외는 모두 `false` 반환으로 처리합니다.
     *
     * @param waitTime 락 획득 최대 대기 시간
     * @param leaseTime 락 보유(TTL) 최대 시간
     * @return 락 획득 성공 시 `true`, 실패 또는 오류 시 `false`
     */
    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds

        do {
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
                log.debug { "락 획득 성공: lockKey=$lockKey" }
                return true
            }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0L) {
                // AWS full jitter: sleep ∈ [1ms, retryDelay) — 동일 retry 윈도우에 인스턴스가 몰리는 것을 방지
                val jitter = Random.nextLong(1, retryDelay.inWholeMilliseconds.coerceAtLeast(2))
                Thread.sleep(minOf(jitter, remaining))
            }
        } while (System.currentTimeMillis() < deadline)

        log.debug { "락 획득 실패 (타임아웃): lockKey=$lockKey" }
        return false
    }

    /**
     * 현재 인스턴스(토큰)가 락을 보유하고 있는지 확인합니다.
     *
     * 리스 만료 후 타 인스턴스가 재획득한 경우 `false`를 반환합니다.
     */
    fun isHeldByCurrentInstance(): Boolean =
        collection.find(
            Filters.and(Filters.eq("_id", lockKey), Filters.eq("token", token))
        ).first() != null

    /**
     * 현재 인스턴스가 보유한 락을 해제합니다.
     *
     * 토큰 불일치 (리스 만료로 인한 타 인스턴스 재획득 등) 시 경고 로그만 남깁니다.
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
     * 락의 expireAt 을 [leaseTime] 만큼 연장합니다.
     *
     * @deprecated [extendDetailed] 사용 권장 — R6 filter 적용 + [ExtendOutcome] 반환.
     */
    @Deprecated(
        message = "Use extendDetailed(leaseTime) for R6 filter (expireAt > now) and ExtendOutcome result.",
        replaceWith = ReplaceWith("extendDetailed(leaseTime).isExtended"),
    )
    fun extend(leaseTime: Duration): Boolean = extendDetailed(leaseTime).isExtended

    /**
     * 락의 expireAt 을 [leaseTime] 만큼 atomic 하게 연장하고 [ExtendOutcome] 을 반환합니다.
     *
     * ## R6 filter (Issue #79 PR 4)
     * 필터에 `expireAt > now()` 를 추가하여 만료된 문서를 다른 인스턴스가 재획득한 race 상황에서
     * stale token 으로 expired-doc 을 revival 시키는 split-brain 을 차단합니다.
     *
     * ## 반환
     * - matchedCount == 1 → [ExtendOutcome.Extended] ( `observedExpireAt = now + leaseTime` best-effort )
     * - matchedCount == 0 → [ExtendOutcome.NotHeld] (토큰 불일치 / lease 만료 / takeover 발생)
     * - backend exception 은 caller (delegate) 가 [ExtendOutcome.BackendError] 로 wrap — 본 함수는 그대로 throw
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
