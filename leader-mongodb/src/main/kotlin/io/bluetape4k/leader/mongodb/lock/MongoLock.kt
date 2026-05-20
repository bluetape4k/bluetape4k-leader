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
import io.bluetape4k.leader.mongodb.internal.MonotonicDeadline
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
 * Token-based distributed lock backed by MongoDB `findOneAndUpdate` upsert + TTL index.
 *
 * ## How it works
 * - `findOneAndUpdate(filter=expired, upsert=true)`: Atomically acquires the lock by updating an expired lock document or inserting a new one
 * - E11000 Duplicate Key: A valid lock already exists → returns `null` and retries
 * - TTL index (`expireAt`, expireAfterSeconds=0): MongoDB automatically removes expired documents
 *
 * ## Design considerations
 * - [leaseTime] must be sufficiently larger than the maximum action execution time.
 *   After TTL expiry, another instance can re-acquire the lock (takeover risk).
 * - In a Replica Set environment, `WriteConcern.MAJORITY` is recommended.
 *   `ACKNOWLEDGED` carries a split-brain risk on network partition.
 * - Token-based, so not thread-bound and safe in Virtual Thread environments.
 *
 * @param collection [MongoCollection] used to store lock state
 * @param lockKey Lock identification key
 * @param retryDelay Base wait time for retries (including jitter)
 */
class MongoLock private constructor(
    private val collection: MongoCollection<Document>,
    val lockKey: String,
    private val retryDelay: Duration,
) {
    companion object : KLogging() {
        /** Collection name for single-leader election */
        const val LOCK_COLLECTION_NAME = "bluetape4k_leader_locks"

        /** Collection name for group leader election */
        const val GROUP_LOCK_COLLECTION_NAME = "bluetape4k_leader_group_locks"

        private val ensuredNamespaces: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * Creates a TTL index (`expireAt`, expireAfterSeconds=0) on the collection.
         *
         * Runs only once per namespace. On index creation failure, removes the namespace from
         * the guard set so the next call can retry.
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

        /** Resets the ensureIndexes state for a specific namespace in tests. */
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

    /**
     * Attempts to acquire the lock within [waitTime]. Returns `true` on success, `false` on timeout or error.
     *
     * This function never throws. All MongoDB exceptions are handled by returning `false`.
     *
     * @param waitTime Maximum wait time for lock acquisition
     * @param leaseTime Maximum lock hold (TTL) duration
     * @return `true` if the lock was acquired, `false` on failure or error
     */
    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = MonotonicDeadline.fromNow(waitTime)

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
     * Checks whether the current instance (token) holds the lock.
     *
     * Returns `false` if the lease has expired and another instance has re-acquired the lock.
     */
    fun isHeldByCurrentInstance(): Boolean =
        collection.find(
            Filters.and(Filters.eq("_id", lockKey), Filters.eq("token", token))
        ).first() != null

    /**
     * Releases the lock held by the current instance.
     *
     * Only logs a warning on token mismatch (e.g., when another instance has re-acquired the lock after lease expiry).
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
     * Atomically extends the lock's expireAt by [leaseTime] and returns an [ExtendOutcome].
     *
     * ## R6 filter (Issue #79 PR 4)
     * Adds `expireAt > now()` to the filter to prevent a stale token from reviving an expired document
     * in a race where another instance has already re-acquired the lock (split-brain protection).
     *
     * ## Return values
     * - matchedCount == 1 → [ExtendOutcome.Extended] (`observedExpireAt = now + leaseTime`, best-effort)
     * - matchedCount == 0 → [ExtendOutcome.NotHeld] (token mismatch / lease expired / takeover occurred)
     * - Backend exceptions are wrapped by the caller (delegate) as [ExtendOutcome.BackendError] — this function throws as-is
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
