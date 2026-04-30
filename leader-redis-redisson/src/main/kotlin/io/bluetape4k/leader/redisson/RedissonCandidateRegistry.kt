package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import org.redisson.api.RedissonClient
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Redisson [RMapCache][org.redisson.api.RMapCache] 를 이용한 후보 레지스트리입니다.
 *
 * ## 저장 구조
 * - Key: `leader:strategy:candidates:{lockName}`
 * - Field: `nodeId`, Value: [CandidateInfo]
 * - 항목별 TTL: [registerCandidate] 호출 시 설정 (heartbeat 역할)
 *
 * ## 분산 일관성 주의
 * [updateResult] 는 read-modify-write 이므로 완전한 원자성을 보장하지 않습니다.
 * winner 노드만 자신의 항목을 갱신하므로 실제 충돌 가능성은 낮습니다.
 *
 * @param redissonClient Redisson 클라이언트
 */
internal class RedissonCandidateRegistry(private val redissonClient: RedissonClient) {

    private fun cacheKey(lockName: String) = "leader:strategy:candidates:$lockName"

    private fun mapCacheFor(lockName: String) =
        redissonClient.getMapCache<String, CandidateInfo>(cacheKey(lockName))

    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        val cache = mapCacheFor(lockName)
        if (ttl == Duration.ZERO) {
            cache.put(info.nodeId, info)
        } else {
            cache.put(info.nodeId, info, ttl.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    fun unregisterCandidate(lockName: String, nodeId: String) {
        mapCacheFor(lockName).remove(nodeId)
    }

    fun listCandidates(lockName: String): List<CandidateInfo> =
        mapCacheFor(lockName).readAllValues().toList()

    fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        val cache = mapCacheFor(lockName)
        val current = cache[nodeId] ?: return
        val updated = current.withResult(result)
        val remainMs = cache.remainTimeToLive(nodeId)  // -1 = no TTL, -2 = absent
        when {
            remainMs > 0L   -> cache.put(nodeId, updated, remainMs, TimeUnit.MILLISECONDS)
            remainMs == -1L -> cache.put(nodeId, updated)
            // remainMs == -2: key expired between GET and TTL check — skip to avoid zombie
        }
    }
}
