package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.logging.KLogging
import org.redisson.api.RedissonClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.TimeUnit

/**
 * Candidate registry backed by Redisson [RMapCache][org.redisson.api.RMapCache].
 *
 * ## Storage Structure
 * - Key: `leader:strategy:candidates:{lockName}`
 * - Field: `nodeId`, Value: [CandidateInfo]
 * - Per-entry TTL: set on each [registerCandidate] call (serves as a heartbeat)
 *
 * ## Distributed Consistency Warning
 * [updateResult] is a read-modify-write operation and does not guarantee full atomicity.
 * In practice, collision risk is low because only the winner node updates its own entry.
 *
 * @param redissonClient Redisson client
 */
internal class RedissonCandidateRegistry(private val redissonClient: RedissonClient) {

    companion object: KLogging()

    private fun cacheKey(lockName: String) = "leader:strategy:candidates:$lockName"

    private fun mapCacheFor(lockName: String) =
        redissonClient.getMapCache<String, CandidateInfo>(cacheKey(lockName))

    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        val cache = mapCacheFor(lockName)
        if (ttl == Duration.ZERO) {
            cache.put(info.nodeId, info)
        } else {
            cache.put(info.nodeId, info, ttl.inWholeMilliseconds, TimeUnit.MILLISECONDS)
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
