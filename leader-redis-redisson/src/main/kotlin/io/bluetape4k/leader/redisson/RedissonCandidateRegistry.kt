package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.redisson.RedissonObject
import org.redisson.api.RedissonClient
import org.redisson.api.RLock
import org.redisson.api.RMapCache
import org.redisson.api.RScript
import org.redisson.client.codec.Codec
import org.redisson.client.codec.StringCodec
import org.redisson.client.protocol.Encoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
        private val ENTRY_LOCK_ATTEMPT_TIMEOUT = 500.milliseconds
        private val ENTRY_LOCK_SOURCE_DEADLINE = 2.seconds
        private val ENTRY_LOCK_CLEANUP_TIMEOUT = 1.seconds
        // Redisson은 owner ID를 lock hash의 `clientId:threadId`로 저장한다.
        // Blocking 경로는 JVM thread ID(양수)를 사용하므로 같은 client에서도
        // coroutine entry lock은 충돌하지 않는 음수 namespace를 사용한다.
        private val suspendLockThreadIds = AtomicLong(Long.MIN_VALUE)

        private const val LOCK_WAITING = 0
        private const val LOCK_ACQUIRED = 1
        private const val LOCK_CANCELLED = 2
        private const val LOCK_CLEANUP_SCHEDULED = 3
        private const val LOCK_FAILED = 4
        private const val LOCK_COMPLETED = 5

        /**
         * RMapCache의 live entry 확인과 metadata/TTL 갱신을 하나의 Redis 연산으로 묶습니다.
         * GET 후 PUT을 분리하면 두 명령 사이에 timeout zset이 만료되어 PUT이 후보를 부활시킬 수 있습니다.
         */
        private const val REFRESH_CANDIDATE_SCRIPT = """
            local mapKey = ARGV[1]
            local currentTime = redis.call('time')
            local currentTimeMillis = tonumber(currentTime[1]) * 1000 + math.floor(tonumber(currentTime[2]) / 1000)
            local packedValue = redis.call('hget', KEYS[1], mapKey)
            if packedValue == false then
                return 0
            end

            local idleDelta, oldValue = struct.unpack('dLc0', packedValue)
            if oldValue ~= ARGV[2] then
                return 0
            end
            local expireDate = 92233720368547758
            local expireDateScore = redis.call('zscore', KEYS[2], mapKey)
            if expireDateScore ~= false then
                expireDate = tonumber(expireDateScore)
            end
            if idleDelta ~= 0 then
                local expireIdle = redis.call('zscore', KEYS[3], mapKey)
                if expireIdle ~= false then
                    expireDate = math.min(expireDate, tonumber(expireIdle))
                end
            end
            if expireDate <= currentTimeMillis then
                redis.call('hdel', KEYS[1], mapKey)
                redis.call('zrem', KEYS[2], mapKey)
                redis.call('zrem', KEYS[3], mapKey)
                redis.call('zrem', KEYS[4], mapKey)
                return 0
            end

            local ttl = tonumber(ARGV[4])
            if ttl > 0 then
                redis.call('zadd', KEYS[2], currentTimeMillis + ttl, mapKey)
            else
                redis.call('zrem', KEYS[2], mapKey)
            end
            redis.call('zrem', KEYS[3], mapKey)

            local refreshedValue = struct.pack('dLc0', 0, string.len(ARGV[3]), ARGV[3])
            redis.call('hset', KEYS[1], mapKey, refreshedValue)

            local maxSize = tonumber(redis.call('hget', KEYS[5], 'max-size'))
            if maxSize ~= nil and maxSize ~= 0 then
                local mode = redis.call('hget', KEYS[5], 'mode')
                if mode == false or mode == 'LRU' then
                    redis.call('zadd', KEYS[4], currentTimeMillis, mapKey)
                else
                    redis.call('zincrby', KEYS[4], 1, mapKey)
                end
            end

            return 1
        """
    }

    private class RefreshScriptMapKey(val value: String)

    private class RefreshScriptMapValue(val value: CandidateInfo)

    private class RefreshScriptTtl(val value: Long)

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
        requireRefreshTtl(ttl)
        val cache = mapCacheFor(lockName)
        withEntryLock(cache, info.nodeId) {
            val current = cache.get(info.nodeId) ?: return@withEntryLock
            refreshCandidateAtomically(cache, current, current.copy(metadata = info.metadata), ttl)
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
        requireRefreshTtl(ttl)
        val cache = mapCacheFor(lockName)
        withEntryLockSuspending(cache, info.nodeId) {
            val current = cache.getAsync(info.nodeId).await() ?: return@withEntryLockSuspending
            refreshCandidateAtomicallySuspending(cache, current, current.copy(metadata = info.metadata), ttl)
        }
    }

    private fun refreshCandidateAtomically(
        cache: RMapCache<String, CandidateInfo>,
        current: CandidateInfo,
        refreshed: CandidateInfo,
        ttl: Duration,
    ): Boolean {
        val result = redissonClient.getScript(refreshScriptCodec(cache)).eval<Long>(
            cache.name,
            RScript.Mode.READ_WRITE,
            REFRESH_CANDIDATE_SCRIPT,
            RScript.ReturnType.LONG,
            mapCacheScriptKeys(cache),
            RefreshScriptMapKey(current.nodeId),
            RefreshScriptMapValue(current),
            RefreshScriptMapValue(refreshed),
            RefreshScriptTtl(ttl.inWholeMilliseconds),
        )
        return result == 1L
    }

    private suspend fun refreshCandidateAtomicallySuspending(
        cache: RMapCache<String, CandidateInfo>,
        current: CandidateInfo,
        refreshed: CandidateInfo,
        ttl: Duration,
    ): Boolean {
        val result = redissonClient.getScript(refreshScriptCodec(cache))
            .evalAsync<Long>(
                cache.name,
                RScript.Mode.READ_WRITE,
                REFRESH_CANDIDATE_SCRIPT,
                RScript.ReturnType.LONG,
                mapCacheScriptKeys(cache),
                RefreshScriptMapKey(current.nodeId),
                RefreshScriptMapValue(current),
                RefreshScriptMapValue(refreshed),
                RefreshScriptTtl(ttl.inWholeMilliseconds),
            )
            .toCompletableFuture()
            .await()
        return result == 1L
    }

    /**
     * RScript는 모든 ARGV를 value encoder 하나로 직렬화하므로 RMapCache의 map key/value
     * encoder와 Lua가 읽는 TTL encoder를 인자별 marker로 선택합니다.
     */
    private fun refreshScriptCodec(cache: RMapCache<String, CandidateInfo>): Codec {
        val delegate = cache.codec
        return object : Codec by delegate {
            private val valueEncoder = Encoder { value ->
                when (value) {
                    is RefreshScriptMapKey -> delegate.mapKeyEncoder.encode(value.value)
                    is RefreshScriptMapValue -> delegate.mapValueEncoder.encode(value.value)
                    is RefreshScriptTtl -> StringCodec.INSTANCE.valueEncoder.encode(value.value.toString())
                    else -> error("지원하지 않는 refresh script 인자입니다: ${value::class.qualifiedName}")
                }
            }

            override fun getValueEncoder(): Encoder = valueEncoder
        }
    }

    private fun requireRefreshTtl(ttl: Duration) {
        require(!ttl.isNegative()) { "refresh ttl은 음수가 될 수 없습니다: $ttl" }
    }

    private fun mapCacheScriptKeys(cache: RMapCache<String, CandidateInfo>): List<Any> {
        val name = (cache as RedissonObject).rawName
        return listOf(
            name,
            RedissonObject.prefixName("redisson__timeout__set", name),
            RedissonObject.prefixName("redisson__idle__set", name),
            RedissonObject.prefixName("redisson__map_cache__last_access__set", name),
            RedissonObject.suffixName(name, "redisson_options"),
        ).map { it.toByteArray(StandardCharsets.UTF_8) }
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
        // Redisson lock ownership은 thread ID 기반이다. dispatcher thread 하나가 여러
        // suspend coroutine을 실행할 수 있으므로 physical thread ID를 재사용하지 않고
        // logical lock hold마다 ID를 할당한다. 음수 namespace는 blocking JVM thread ID와
        // 충돌하지 않는다.
        val threadId = suspendLockThreadIds.getAndIncrement()
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
     * 무기한 `lockAsync` waiter를 만들지 않도록 finite `tryLockAsync` 시도만 반복합니다.
     * 시도 자체는 500ms로 제한하고, 성공한 entry lock은 `leaseTime=-1`로 Redisson watchdog
     * 갱신을 유지합니다. source future가 backend 지연으로 deadline을 넘기면 source를 취소하고,
     * 취소와 실제 획득이 경합하면 late acquisition을 관찰해 해제합니다.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun awaitEntryLock(lock: RLock, threadId: Long) {
        while (true) {
            if (awaitEntryLockAttempt(lock, threadId)) {
                return
            }
            currentCoroutineContext().ensureActive()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun awaitEntryLockAttempt(lock: RLock, threadId: Long): Boolean =
        withTimeout(ENTRY_LOCK_SOURCE_DEADLINE) {
            suspendCancellableCoroutine { continuation ->
                val state = AtomicInteger(LOCK_WAITING)
                val lockFuture = try {
                    lock.tryLockAsync(
                        ENTRY_LOCK_ATTEMPT_TIMEOUT.inWholeMilliseconds,
                        -1L,
                        TimeUnit.MILLISECONDS,
                        threadId,
                    )
                } catch (failure: Throwable) {
                    state.compareAndSet(LOCK_WAITING, LOCK_FAILED)
                    continuation.resumeWithException(failure)
                    return@suspendCancellableCoroutine
                }

                lockFuture.whenComplete { acquired, failure ->
                    if (failure != null) {
                        if (state.compareAndSet(LOCK_WAITING, LOCK_FAILED)) {
                            continuation.resumeWithException(failure.unwrapCompletionException())
                        }
                    } else if (acquired == true) {
                        when {
                            state.compareAndSet(LOCK_WAITING, LOCK_ACQUIRED) -> continuation.resume(true)
                            state.compareAndSet(LOCK_CANCELLED, LOCK_CLEANUP_SCHEDULED) ->
                                scheduleLateEntryLockCleanup(lock, threadId)
                        }
                    } else if (state.compareAndSet(LOCK_WAITING, LOCK_COMPLETED)) {
                        continuation.resume(false)
                    }
                }

                continuation.invokeOnCancellation {
                    while (true) {
                        when (state.get()) {
                            LOCK_WAITING -> if (state.compareAndSet(LOCK_WAITING, LOCK_CANCELLED)) {
                                lockFuture.cancel(false)
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

    /** Unlock 응답은 취소하지 않고 관찰하여 backend의 늦은 정리를 보존합니다. */
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
