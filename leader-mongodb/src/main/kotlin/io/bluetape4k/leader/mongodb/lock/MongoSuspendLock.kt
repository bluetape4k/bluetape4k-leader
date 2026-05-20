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
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.leader.mongodb.internal.MonotonicDeadline
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.bson.Document
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coroutine token-based distributed lock using MongoDB `findOneAndUpdate` upsert + TTL index.
 *
 * Uses the same lock strategy as [MongoLock], but exposed as `suspend` functions.
 * Uses `delay()` instead of blocking `Thread.sleep` so the coroutine thread is not held.
 *
 * ## Cancellation safety
 * `unlock()` is vulnerable to coroutine cancellation.
 * **It must always be called inside a `withContext(NonCancellable)` block.**
 * Calling it directly from a cancelled context causes an immediate CancellationException.
 * Election implementations (e.g. [MongoSuspendLeaderElector]) are responsible for this guarantee.
 *
 * ## Design notes
 * - [leaseTime] must be sufficiently larger than the maximum action execution time.
 * - `WriteConcern.MAJORITY` is recommended in a Replica Set environment.
 * - Token-based, so safe regardless of coroutine thread switches.
 *
 * @param collection coroutine [MongoCollection] storing the lock state
 * @param lockKey lock identification key
 * @param retryDelay base retry wait time (includes jitter)
 */
class MongoSuspendLock private constructor(
    private val collection: MongoCollection<Document>,
    val lockKey: String,
    private val retryDelay: Duration,
) {
    companion object : KLoggingChannel() {
        private val ensuredNamespaces: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * Creates a TTL index (`expireAt`, expireAfterSeconds=0) on the collection.
         *
         * Runs only once per namespace. On failure, removes the namespace from the guard set
         * so that the next call can retry.
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

        /** Resets the ensureIndexes state for a specific namespace in tests. */
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

    internal val token: String = Base58.randomString(22)

    /**
     * Attempts to acquire the lock within [waitTime]. Returns `true` on success,
     * `false` on timeout or error.
     *
     * This function never throws exceptions (except `CancellationException`, which is propagated).
     * On coroutine cancellation, `ensureActive()` raises `CancellationException` to break the loop.
     *
     * @param waitTime maximum wait time for lock acquisition
     * @param leaseTime maximum lock hold (TTL) duration
     * @return `true` on successful lock acquisition, `false` on failure or error
     */
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

    /**
     * Checks whether the current instance (token) holds the lock.
     */
    suspend fun isHeldByCurrentInstance(): Boolean =
        collection.countDocuments(
            Filters.and(Filters.eq("_id", lockKey), Filters.eq("token", token))
        ) > 0

    /**
     * Releases the lock held by the current instance.
     *
     * **Must always be called inside a `withContext(NonCancellable)` block.**
     * Calling it directly from a cancelled context causes an immediate CancellationException.
     *
     * Logs a warning on token mismatch without throwing.
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

    /**
     * Atomically extends the lock's `expireAt` by [leaseTime] and returns an [ExtendOutcome].
     *
     * ## R6 filter (Issue #79 PR 4)
     * Adds `expireAt > now()` to the filter to block split-brain scenarios where a stale token
     * could revive an expired document that another instance has already re-acquired.
     *
     * ## Return values
     * - matchedCount == 1 → [ExtendOutcome.Extended] (`observedExpireAt = now + leaseTime`, best-effort)
     * - matchedCount == 0 → [ExtendOutcome.NotHeld] (token mismatch / lease expired / takeover occurred)
     * - Backend exceptions are wrapped as [ExtendOutcome.BackendError] by the caller (delegate) —
     *   this function rethrows them as-is.
     *
     * The coroutine driver is reactive-native, so `withContext(Dispatchers.IO)` is not needed.
     */
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
