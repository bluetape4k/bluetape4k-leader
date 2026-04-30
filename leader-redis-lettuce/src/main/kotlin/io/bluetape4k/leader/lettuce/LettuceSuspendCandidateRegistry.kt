@file:OptIn(ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import java.time.Duration

/**
 * Lettuce coroutines API 기반 suspend 후보 레지스트리입니다.
 *
 * [LettuceCandidateRegistry] 의 suspend 버전으로 [RedisCoroutinesCommands] 를 사용합니다.
 * Lettuce Netty 비동기 I/O 기반이므로 [kotlinx.coroutines.Dispatchers.IO] 전환이 불필요합니다.
 *
 * @param connection Lettuce StatefulRedisConnection (StringCodec 기반)
 */
internal class LettuceSuspendCandidateRegistry(
    connection: StatefulRedisConnection<String, String>,
) {
    private val cmds: RedisCoroutinesCommands<String, String> = connection.coroutines()

    private fun candidateKey(lockName: String, nodeId: String) =
        "leader:strategy:candidates:$lockName:$nodeId"

    private fun keyPattern(lockName: String) =
        "leader:strategy:candidates:$lockName:*"

    private fun validateLockName(lockName: String) {
        require(lockName.isNotBlank()) { "lockName must not be blank" }
        require(lockName.none { it in "*?[]\\" }) { "lockName must not contain SCAN glob metacharacters: $lockName" }
    }

    /**
     * 후보를 등록하거나 갱신합니다.
     *
     * [ttl] = [Duration.ZERO] 이면 TTL 없이 영구 저장합니다.
     */
    suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        validateLockName(lockName)
        val key = candidateKey(lockName, info.nodeId)
        val value = LettuceCandidateInfoCodec.encode(info)
        if (ttl == Duration.ZERO) cmds.set(key, value)
        else cmds.psetex(key, ttl.toMillis(), value)
    }

    /** 후보를 등록 해제합니다. 존재하지 않는 nodeId 는 무시됩니다. */
    suspend fun unregisterCandidate(lockName: String, nodeId: String) {
        validateLockName(lockName)
        cmds.del(candidateKey(lockName, nodeId))
    }

    /** [lockName] 에 등록된 현재 후보 목록을 반환합니다. */
    suspend fun listCandidates(lockName: String): List<CandidateInfo> {
        validateLockName(lockName)
        val keys = scanKeys(lockName)
        if (keys.isEmpty()) return emptyList()
        return cmds.mget(*keys.toTypedArray())
            .mapNotNull { kv -> if (kv.hasValue()) LettuceCandidateInfoCodec.decode(kv.getValue()) else null }
            .toList()
    }

    /**
     * 작업 결과를 후보 정보에 반영합니다.
     *
     * `SET key value XX KEEPTTL` 로 만료된 키의 좀비 복원을 방지합니다.
     */
    suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        validateLockName(lockName)
        val key = candidateKey(lockName, nodeId)
        val current = cmds.get(key)?.let { LettuceCandidateInfoCodec.decode(it) } ?: return
        val updated = LettuceCandidateInfoCodec.encode(current.withResult(result))
        cmds.set(key, updated, SetArgs.Builder.xx().keepttl())
    }

    private suspend fun scanKeys(lockName: String): List<String> {
        val args = ScanArgs.Builder.matches(keyPattern(lockName)).limit(1000)
        val keys = mutableListOf<String>()
        var cursor: ScanCursor = ScanCursor.INITIAL
        do {
            val result = cmds.scan(cursor, args) ?: break
            keys += result.keys
            cursor = result
        } while (!cursor.isFinished)
        return keys
    }
}
