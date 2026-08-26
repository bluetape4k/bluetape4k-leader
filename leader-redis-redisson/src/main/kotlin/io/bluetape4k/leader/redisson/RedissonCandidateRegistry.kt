package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import org.redisson.api.RedissonClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.TimeUnit

/**
 * `RedissonCandidateRegistry`는 Redis Redisson backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property redissonClient Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 */
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
        // Redisson의 per-entry lock과 server-side fastPutIfExists를 사용해
        // read-modify-write 및 GET/TTL/PUT 사이의 만료 경쟁을 제거한다.
        cache.computeIfPresent(nodeId) { _, current -> current.withResult(result) }
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
}
