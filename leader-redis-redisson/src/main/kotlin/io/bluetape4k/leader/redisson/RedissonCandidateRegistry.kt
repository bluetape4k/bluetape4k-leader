package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.redisson.api.RedissonClient
import org.redisson.api.RLock
import org.redisson.api.RMapCache
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * `RedissonCandidateRegistry`는 Redis Redisson backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property redissonClient Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 */
@Suppress("TooManyFunctions")
internal class RedissonCandidateRegistry(
    private val redissonClient: RedissonClient,
    private val keyPrefix: String = DEFAULT_KEY_PREFIX,
) {
    /** 기존 internal JVM constructor descriptor를 보존합니다. */
    internal constructor(redissonClient: RedissonClient) : this(
        redissonClient,
        DEFAULT_KEY_PREFIX,
    )

    companion object: KLogging() {
        internal const val DEFAULT_KEY_PREFIX = "leader:strategy:candidates"
        internal const val GROUP_KEY_PREFIX = "leader:strategy:group-candidates:redisson:v1"
        private val ENTRY_LOCK_CLEANUP_TIMEOUT = 1.seconds
        private val suspendLockThreadIds = AtomicLong()

        private const val LOCK_WAITING = 0
        private const val LOCK_ACQUIRED = 1
        private const val LOCK_CANCELLED = 2
        private const val LOCK_CLEANUP_SCHEDULED = 3
        private const val LOCK_FAILED = 4
    }

    private fun cacheKey(lockName: String): String {
        validateLockName(lockName)
        return "$keyPrefix:$lockName"
    }

    private fun mapCacheFor(lockName: String) =
        redissonClient.getMapCache<String, CandidateInfo>(cacheKey(lockName))

    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        val cache = mapCacheFor(lockName)
        withEntryLock(cache, info.nodeId) {
            if (ttl == Duration.ZERO) {
                cache.put(info.nodeId, info)
            } else {
                cache.put(info.nodeId, info, ttl.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        val cache = mapCacheFor(lockName)
        withEntryLock(cache, info.nodeId) {
            val current = cache.get(info.nodeId) ?: return@withEntryLock
            val refreshed = current.copy(metadata = info.metadata)
            if (ttl == Duration.ZERO) {
                cache.put(info.nodeId, refreshed)
            } else {
                cache.put(info.nodeId, refreshed, ttl.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun unregisterCandidate(lockName: String, nodeId: String) {
        val cache = mapCacheFor(lockName)
        withEntryLock(cache, nodeId) {
            cache.remove(nodeId)
        }
    }

    fun listCandidates(lockName: String): List<CandidateInfo> =
        mapCacheFor(lockName).readAllValues().toList()

    fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        val cache = mapCacheFor(lockName)
        // Redisson의 per-entry lock과 server-side computeIfPresent를 사용해
        // heartbeat/read-modify-write와의 경합 및 GET/TTL/PUT 사이의 만료 경쟁을 제거한다.
        withEntryLock(cache, nodeId) {
            cache.computeIfPresent(nodeId) { _, current -> current.withResult(result) }
        }
    }

    /**
     * Redisson `RFuture`를 coroutine cancellation과 연결한 후보 등록입니다.
     * 동기 `put`을 `Dispatchers.IO`에서 감싸면 네트워크 대기 중 취소가 호출자에게
     * 즉시 전파되지 않으므로, suspend strategic 경로는 이 메서드를 사용합니다.
     */
    suspend fun registerCandidateSuspending(lockName: String, info: CandidateInfo, ttl: Duration) {
        val cache = mapCacheFor(lockName)
        withEntryLockSuspending(cache, info.nodeId) {
            if (ttl == Duration.ZERO) {
                cache.putAsync(info.nodeId, info).await()
            } else {
                cache.putAsync(info.nodeId, info, ttl.inWholeMilliseconds, TimeUnit.MILLISECONDS).await()
            }
        }
    }

    suspend fun refreshCandidateSuspending(lockName: String, info: CandidateInfo, ttl: Duration) {
        val cache = mapCacheFor(lockName)
        withEntryLockSuspending(cache, info.nodeId) {
            val current = cache.getAsync(info.nodeId).await() ?: return@withEntryLockSuspending
            val refreshed = current.copy(metadata = info.metadata)
            if (ttl == Duration.ZERO) {
                cache.putAsync(info.nodeId, refreshed).await()
            } else {
                cache.putAsync(
                    info.nodeId,
                    refreshed,
                    ttl.inWholeMilliseconds,
                    TimeUnit.MILLISECONDS,
                ).await()
            }
        }
    }

    /** Redisson `RFuture` 기반 후보 해제입니다. */
    suspend fun unregisterCandidateSuspending(lockName: String, nodeId: String) {
        val cache = mapCacheFor(lockName)
        withEntryLockSuspending(cache, nodeId) {
            cache.removeAsync(nodeId).await()
        }
    }

    /** Redisson `RFuture` 기반 후보 조회입니다. */
    suspend fun listCandidatesSuspending(lockName: String): List<CandidateInfo> =
        mapCacheFor(lockName).readAllValuesAsync().await().toList()

    /** Redisson `RFuture` 기반 결과 갱신입니다. */
    suspend fun updateResultSuspending(lockName: String, nodeId: String, result: CandidateResult) {
        val cache = mapCacheFor(lockName)
        withEntryLockSuspending(cache, nodeId) {
            val current = cache.getAsync(nodeId).await() ?: return@withEntryLockSuspending
            cache.fastPutIfExistsAsync(nodeId, current.withResult(result)).await()
        }
    }

    /** 등록·해제도 결과 갱신과 Redisson map entry lock을 공유하도록 보장합니다. */
    private inline fun <T> withEntryLock(
        cache: org.redisson.api.RMapCache<String, CandidateInfo>,
        nodeId: String,
        action: () -> T,
    ): T {
        val lock = cache.getLock(nodeId)
        lock.lock()
        return try {
            action()
        } finally {
            lock.unlock()
        }
    }

    private suspend inline fun <T> withEntryLockSuspending(
        cache: RMapCache<String, CandidateInfo>,
        nodeId: String,
        crossinline action: suspend () -> T,
    ): T {
        val lock = cache.getLock(nodeId)
        // Redisson lock ownership is thread-id based. A dispatcher thread may
        // host multiple suspended coroutines, so allocate one id per logical
        // lock hold instead of reusing the physical thread id.
        val threadId = suspendLockThreadIds.incrementAndGet()
        var acquired = false
        return try {
            awaitEntryLock(lock, threadId)
            acquired = true
            action()
        } finally {
            if (acquired) {
                unlockEntryLockBounded(lock, threadId)
            }
        }
    }

    /**
     * Redisson의 `RFuture.await`는 coroutine 취소 시 원본 future까지 취소합니다.
     * lock 명령은 취소 응답과 서버의 실제 획득 시점이 어긋날 수 있으므로, 원본 future를
     * 취소하지 않고 late acquisition을 관찰해 반드시 해제하도록 별도 상태 기계를 둡니다.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun awaitEntryLock(lock: RLock, threadId: Long) {
        val state = AtomicInteger(LOCK_WAITING)
        suspendCancellableCoroutine<Unit> { continuation ->
            val lockFuture = try {
                lock.lockAsync(threadId)
            } catch (failure: Throwable) {
                state.compareAndSet(LOCK_WAITING, LOCK_FAILED)
                continuation.resumeWithException(failure)
                return@suspendCancellableCoroutine
            }

            lockFuture.whenComplete { _, failure ->
                if (failure == null) {
                    when {
                        state.compareAndSet(LOCK_WAITING, LOCK_ACQUIRED) -> continuation.resume(Unit)
                        state.compareAndSet(LOCK_CANCELLED, LOCK_CLEANUP_SCHEDULED) ->
                            scheduleLateEntryLockCleanup(lock, threadId)
                    }
                } else if (state.compareAndSet(LOCK_WAITING, LOCK_FAILED)) {
                    continuation.resumeWithException(failure.unwrapCompletionException())
                }
            }

            continuation.invokeOnCancellation {
                while (true) {
                    when (state.get()) {
                        LOCK_WAITING -> if (state.compareAndSet(LOCK_WAITING, LOCK_CANCELLED)) {
                            return@invokeOnCancellation
                        }
                        LOCK_ACQUIRED -> if (state.compareAndSet(LOCK_ACQUIRED, LOCK_CLEANUP_SCHEDULED)) {
                            scheduleLateEntryLockCleanup(lock, threadId)
                            return@invokeOnCancellation
                        }
                        else -> return@invokeOnCancellation
                    }
                }
            }
        }
    }

    /** 취소되지 않은 정상 경로의 unlock도 backend 응답 지연으로 호출자를 붙잡지 않도록 제한합니다. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun unlockEntryLockBounded(lock: RLock, threadId: Long) {
        withContext(NonCancellable) {
            try {
                val released = kotlinx.coroutines.withTimeoutOrNull(ENTRY_LOCK_CLEANUP_TIMEOUT) {
                    lock.unlockAsync(threadId).awaitWithoutCancellingSource()
                    true
                } ?: false
                if (!released) {
                    log.warn {
                        "Redisson entry lock cleanup timed out. threadId=$threadId"
                    }
                }
            } catch (failure: Exception) {
                log.warn(failure) {
                    "Redisson entry lock cleanup failed. threadId=$threadId"
                }
            }
        }
    }

    /** 취소된 caller와 독립적으로 late acquisition의 unlock 명령만 bounded하게 예약합니다. */
    @Suppress("TooGenericExceptionCaught")
    private fun scheduleLateEntryLockCleanup(lock: RLock, threadId: Long) {
        try {
            lock.unlockAsync(threadId)
                .toCompletableFuture()
                .orTimeout(ENTRY_LOCK_CLEANUP_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .whenComplete { _, failure ->
                    if (failure != null) {
                        val cleanupFailure = failure.unwrapCompletionException()
                        if (cleanupFailure is TimeoutException) {
                            log.warn {
                                "Redisson late entry lock cleanup timed out. threadId=$threadId"
                            }
                        } else {
                            log.warn(cleanupFailure) {
                                "Redisson late entry lock cleanup failed. threadId=$threadId"
                            }
                        }
                    }
                }
        } catch (failure: Exception) {
            log.warn(failure) {
                "Redisson late entry lock cleanup could not be scheduled. threadId=$threadId"
            }
        }
    }

    /** Source future를 취소하지 않는 await입니다. cancellation handler가 원본 명령을 보존해야 합니다. */
    private suspend fun <T> org.redisson.api.RFuture<T>.awaitWithoutCancellingSource(): T =
        suspendCancellableCoroutine { continuation ->
            whenComplete { value, failure ->
                if (!continuation.isActive) {
                    return@whenComplete
                }
                if (failure == null) {
                    continuation.resume(value)
                } else {
                    continuation.resumeWithException(failure.unwrapCompletionException())
                }
            }
        }

    private fun Throwable.unwrapCompletionException(): Throwable =
        (this as? CompletionException)?.cause ?: this
}
