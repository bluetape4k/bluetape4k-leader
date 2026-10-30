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
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AcquireResult
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.mongodb.internal.MonotonicDeadline
import io.bluetape4k.leader.remainingMinLeaseTime
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `MongoLock`는 MongoDB lock key의 token 기반 tryLock·ownership 확인·unlock·lease 연장을 blocking 경로로 수행합니다.
 *
 * `tryLock`이 경합·timeout으로 ownership을 획득하지 못하면 `false`를 반환하며, unlock/extend는 backend token 일치 여부를 확인해 불일치·만료를
 * 성공으로 처리하지 않습니다.
 * @property collection `_id`, `token`, `expireAt` lock 문서를 저장하는 MongoDB collection입니다.
 * @property lockKey lock 문서의 `_id`입니다. 그룹 elector는 `$lockName:slot:n` 형식의 슬롯 키를 전달합니다.
 * @property retryDelay 경합 재시도 사이의 양의 jitter 상한이며 실제 sleep은 `1ms` 이상 이 값 미만으로 선택됩니다.
 */
class MongoLock private constructor(
    private val collection: MongoCollection<Document>,
    val lockKey: String,
    private val retryDelay: Duration,
) {
    companion object: KLogging() {
        /** 단일 leader lock 문서를 저장할 때 사용하는 기본 collection 이름입니다. */
        const val LOCK_COLLECTION_NAME = "bluetape4k_leader_locks"

        const val DEFAULT_TOKEN_LENGTH = 22

        /** 그룹 slot lock 문서를 저장할 때 사용하는 기본 collection 이름입니다. */
        const val GROUP_LOCK_COLLECTION_NAME = "bluetape4k_leader_group_locks"

        private val ensuredNamespaces: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** namespace별로 `expireAt` TTL index를 한 번 만들며, 생성 실패 시 다음 호출이 재시도할 수 있게 marker를 제거합니다. */
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

        /** 테스트·복구용으로 namespace의 index-created marker를 제거해 다음 `ensureIndexes`가 다시 실행되게 합니다. */
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

    internal val token: String = Base58.randomString(DEFAULT_TOKEN_LENGTH)


    /** atomic upsert로 lock 문서를 획득하고, 경합이면 jitter 재시도를 `waitTime` deadline까지 수행합니다. 오류·시간 초과는 `false`입니다. */
    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = MonotonicDeadline.fromNow(waitTime)

        do {
            when (tryAcquireOnce(leaseTime)) {
                AcquireResult.ACQUIRED -> {
                    log.debug { "락 획득 성공: lockKey=$lockKey" }
                    return true
                }
                AcquireResult.CONTENDED -> Unit
                AcquireResult.FAILED   -> return false
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

    /** atomic upsert 획득을 executor에서 재시도하고 cancellation을 현재 단계로 전달하는 `CompletableFuture` API입니다. */
    @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
    fun tryLockAsync(
        waitTime: Duration,
        leaseTime: Duration,
        executor: Executor = VirtualThreadExecutor,
    ): CompletableFuture<Boolean> {
        val deadline = MonotonicDeadline.fromNow(waitTime)
        val currentStage = AtomicReference<CompletableFuture<*>?>()
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        val cancellationTarget = object: CompletableFuture<Unit>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                currentStage.get()?.cancel(mayInterruptIfRunning)
                return super.cancel(mayInterruptIfRunning)
            }
        }
        cancellationRelay.invoke { cancellationTarget }
        val resultSource = CompletableFuture<Boolean>()
        val result = LeaderFutureBridge.propagateCancellation(resultSource, cancellationRelay)

        fun complete(value: Boolean): Boolean {
            val completed = result.complete(value)
            resultSource.complete(value)
            return completed
        }

        fun completeExceptionally(failure: Throwable): Boolean {
            val completed = result.completeExceptionally(failure)
            resultSource.completeExceptionally(failure)
            return completed
        }

        fun releaseLateAcquisition(released: AtomicBoolean) {
            if (released.compareAndSet(false, true)) {
                runCatching {
                    unlock()
                }.onFailure {
                    log.warn(it) { "취소된 async 락 획득을 반납하지 못했습니다: lockKey=$lockKey" }
                }
            }
        }

        fun registerStage(stage: CompletableFuture<*>): Boolean {
            currentStage.set(stage)
            if (result.isCancelled && currentStage.compareAndSet(stage, null)) {
                stage.cancel(true)
                return false
            }
            return true
        }

        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        fun attempt() {
            if (result.isDone) return

            val lateRelease = AtomicBoolean()
            val acquisition = try {
                futureOf(executor) {
                    if (result.isCancelled) {
                        AcquireResult.FAILED
                    } else {
                        tryAcquireOnce(leaseTime).also { acquired ->
                            if (acquired == AcquireResult.ACQUIRED && result.isCancelled) {
                                releaseLateAcquisition(lateRelease)
                            }
                        }
                    }
                }
            } catch (error: RuntimeException) {
                completeExceptionally(error)
                return
            }
            if (!registerStage(acquisition)) return

            acquisition.whenComplete { acquired, failure ->
                currentStage.compareAndSet(acquisition, null)
                if (failure != null) {
                    if (!result.isDone) completeExceptionally(failure)
                    return@whenComplete
                }
                if (result.isCancelled) {
                    if (acquired == AcquireResult.ACQUIRED) {
                        releaseLateAcquisition(lateRelease)
                    }
                    return@whenComplete
                }

                when (acquired) {
                    AcquireResult.ACQUIRED -> {
                        log.debug { "락 획득 성공 (async): lockKey=$lockKey" }
                        if (!complete(true) && result.isCancelled) {
                            releaseLateAcquisition(lateRelease)
                        }
                    }
                    AcquireResult.FAILED   -> complete(false)
                    AcquireResult.CONTENDED -> {
                        if (!deadline.hasTimeRemaining()) {
                            log.debug { "락 획득 실패 (타임아웃, async): lockKey=$lockKey" }
                            complete(false)
                        } else {
                            val jitter = Random.nextLong(1, retryDelay.inWholeMilliseconds.coerceAtLeast(2))
                            val delayMillis = deadline.remainingMillisForDelay(jitter)
                            val delayed = CompletableFuture.runAsync(
                                {},
                                CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS),
                            )
                            if (registerStage(delayed)) {
                                delayed.whenComplete { _, delayFailure ->
                                    currentStage.compareAndSet(delayed, null)
                                    if (delayFailure == null && !result.isDone) attempt()
                                }
                            }
                        }
                    }
                }
            }
        }

        attempt()
        return result
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
                else  -> {
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

    /** `_id`와 `token`이 현재 인스턴스 값과 일치하는 문서가 있는지 검사합니다. */
    fun isHeldByCurrentInstance(): Boolean =
        collection.find(
            Filters.and(
                Filters.eq("_id", lockKey),
                Filters.eq("token", token)
            )
        ).first() != null

    /** 소유 token을 검증한 뒤 남은 `minLeaseTime`이 있으면 `expireAt`만 갱신하고, 없으면 문서를 삭제합니다. */
    fun unlock(
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
            log.warn { "락 해제 실패 — 토큰 불일치 또는 이미 만료됨: lockKey=$lockKey" }
        } else {
            log.debug { "락 해제 성공: lockKey=$lockKey" }
        }
    }

    /** `_id`·token이 일치하고 기존 `expireAt`이 아직 유효할 때만 lease를 갱신합니다. 결과는 `Extended` 또는 `NotHeld`입니다. */
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

/**
 * 1.0.0에서 노출된 JVM facade를 유지하는 호환용 lock name 검증 함수입니다.
 */
@Deprecated("use String.validateMongoLockName() instead")
@Suppress("DEPRECATION")
fun validateMongoLockName(lockName: String) {
    io.bluetape4k.leader.mongodb.validateMongoLockName(lockName)
}
