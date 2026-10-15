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
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.mongodb.internal.MonotonicDeadline
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.bson.Document
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `MongoSuspendLock`는 MongoDB lock key의 token 기반 tryLock·ownership 확인·unlock·lease 연장을 suspend 경로로 수행합니다.
 *
 * `tryLock`이 경합·timeout으로 ownership을 획득하지 못하면 `false`를 반환하며, unlock/extend는 backend token 일치 여부를 확인해 불일치·만료를 성공으로 처리하지 않습니다.
 * @property collection `_id`, `token`, `expireAt` lock 문서를 저장하는 coroutine MongoDB collection입니다.
 * @property lockKey lock 문서의 `_id`입니다. 그룹 elector는 `$lockName:slot:n` 형식의 슬롯 키를 전달합니다.
 * @property retryDelay 경합 재시도 사이의 양의 jitter 상한이며 실제 delay는 `1ms` 이상 이 값 미만으로 선택됩니다.
 */
class MongoSuspendLock private constructor(
    private val collection: MongoCollection<Document>,
    val lockKey: String,
    private val retryDelay: Duration,
) {
    companion object: KLoggingChannel() {
        private val ensuredNamespaces: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** namespace별로 `expireAt` TTL index를 한 번 만들며, 생성 실패 시 다음 호출이 재시도할 수 있게 marker를 제거합니다. */
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

        /** 테스트·복구용으로 namespace의 index-created marker를 제거해 다음 `ensureIndexes`가 다시 실행되게 합니다. */
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

    internal val token: String = Base58.randomString(MongoLock.DEFAULT_TOKEN_LENGTH)

    /** atomic upsert로 lock 문서를 획득하고, 경합이면 jitter 재시도를 `waitTime` deadline까지 수행합니다. 오류·시간 초과는 `false`입니다. */
    suspend fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = MonotonicDeadline.fromNow(waitTime)

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

            if (deadline.hasTimeRemaining()) {
                // AWS full jitter: delay ∈ [1ms, retryDelay) — 동일 retry 윈도우에 인스턴스가 몰리는 것을 방지
                val jitter = Random.nextLong(1, retryDelay.inWholeMilliseconds.coerceAtLeast(2))
                val delayMillis = deadline.remainingMillisForDelay(jitter)
                if (delayMillis > 0L) {
                    delay(delayMillis.milliseconds)
                }
            }
        } while (deadline.hasTimeRemaining())

        log.debug { "락 획득 실패 (타임아웃, suspend): lockKey=$lockKey" }
        return false
    }

    /** `_id`와 `token`이 현재 인스턴스 값과 일치하는 문서가 있는지 coroutine query로 검사합니다. */
    suspend fun isHeldByCurrentInstance(): Boolean =
        collection.countDocuments(
            Filters.and(
                Filters.eq("_id", lockKey),
                Filters.eq("token", token)
            )
        ) > 0

    /** 소유 token을 검증한 뒤 남은 `minLeaseTime`이 있으면 `expireAt`만 갱신하고, 없으면 문서를 삭제합니다. */
    suspend fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) {
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)
        val matched = if (remaining > Duration.ZERO) {
            collection.updateOne(
                Filters.and(
                    Filters.eq("_id", lockKey),
                    Filters.eq("token", token)
                ),
                Updates.set("expireAt", Date(System.currentTimeMillis() + remaining.inWholeMilliseconds))
            ).matchedCount
        } else {
            collection.deleteOne(
                Filters.and(
                    Filters.eq("_id", lockKey),
                    Filters.eq("token", token)
                )
            ).deletedCount
        }
        if (matched == 0L) {
            log.warn { "락 해제 실패 — 토큰 불일치 또는 이미 만료됨 (suspend): lockKey=$lockKey" }
        } else {
            log.debug { "락 해제 성공 (suspend): lockKey=$lockKey" }
        }
    }

    /** `_id`·token이 일치하고 기존 `expireAt`이 아직 유효할 때만 lease를 갱신하며 `Extended` 또는 `NotHeld`를 반환합니다. */
    suspend fun extendDetailed(leaseTime: Duration): ExtendOutcome {
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
            log.debug { "MongoDB extend 실패 (NotHeld, suspend): lockKey=$lockKey" }
            ExtendOutcome.NotHeld
        }
    }
}
