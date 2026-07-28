package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.logging.warn
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlin.time.Duration

/**
 * `LettuceCandidateRegistry`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property connection Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 */
internal class LettuceCandidateRegistry(
    private val connection: StatefulRedisConnection<String, String>,
) {
    companion object: KLogging() {
        private const val KEY_PREFIX = "leader:strategy:candidates"
    }

    private val sync = connection.sync()

    private fun indexKey(lockName: String) =
        "$KEY_PREFIX:$lockName"

    private fun candidateKey(lockName: String, nodeId: String) =
        "${indexKey(lockName)}:$nodeId"

    private fun validateLockName(lockName: String) {
        lockName.requireNotBlank("lockName")
    }

    /**
     * `registerCandidate` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        validateLockName(lockName)
        val key = candidateKey(lockName, info.nodeId)
        val indexKey = indexKey(lockName)
        val value = LettuceCandidateInfoCodec.encode(info)
        if (ttl == Duration.ZERO) sync.set(key, value)
        else sync.psetex(key, ttl.inWholeMilliseconds, value)
        sync.sadd(indexKey, info.nodeId)
        if (ttl == Duration.ZERO) sync.persist(indexKey)
        else sync.pexpire(indexKey, ttl.inWholeMilliseconds)
    }

    /**
     * `unregisterCandidate` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun unregisterCandidate(lockName: String, nodeId: String) {
        validateLockName(lockName)
        sync.del(candidateKey(lockName, nodeId))
        sync.srem(indexKey(lockName), nodeId)
    }

    /**
     * `listCandidates` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun listCandidates(lockName: String): List<CandidateInfo> {
        validateLockName(lockName)
        val nodeIds = sync.smembers(indexKey(lockName)).toList()
        if (nodeIds.isEmpty()) return emptyList()
        val staleNodeIds = mutableListOf<String>()
        val keys = nodeIds.map { nodeId -> candidateKey(lockName, nodeId) }
        return sync.mget(*keys.toTypedArray())
            .mapNotNull { kv ->
                if (!kv.hasValue()) {
                    kv.key.removePrefix("${indexKey(lockName)}:").takeIf { it.isNotBlank() }?.let(staleNodeIds::add)
                    return@mapNotNull null
                }
                val raw = kv.value
                runCatching { LettuceCandidateInfoCodec.decode(raw) }
                    .onFailure { log.warn(it) { "[$lockName] CandidateInfo 디코딩 실패 — 항목 skip: key=${kv.key}" } }
                    .getOrNull()
            }
            .also {
                if (staleNodeIds.isNotEmpty()) {
                    sync.srem(indexKey(lockName), *staleNodeIds.toTypedArray())
                }
            }
    }

    /**
     * `updateResult` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        validateLockName(lockName)
        val key = candidateKey(lockName, nodeId)
        val current = sync.get(key)?.let { LettuceCandidateInfoCodec.decode(it) } ?: return
        val updated = LettuceCandidateInfoCodec.encode(current.withResult(result))
        sync.set(key, updated, SetArgs.Builder.xx().keepttl())
    }
}
