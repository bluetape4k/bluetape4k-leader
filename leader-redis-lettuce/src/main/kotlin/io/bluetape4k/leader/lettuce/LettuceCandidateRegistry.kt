package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.logging.warn
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Lettuce [StatefulRedisConnection] 기반 후보 레지스트리입니다.
 *
 * ## 저장 구조
 * - Key: `leader:strategy:candidates:{lockName}:{nodeId}`
 * - Value: [LettuceCandidateInfoCodec] 으로 인코딩된 문자열
 * - TTL: `PSETEX` 로 heartbeat 역할 (등록 시 설정)
 *
 * ## 분산 일관성 주의
 * [updateResult] 는 read-modify-write 이므로 완전한 원자성을 보장하지 않습니다.
 * winner 노드만 자신의 항목을 갱신하므로 실제 충돌 가능성은 낮습니다.
 *
 * @param connection Lettuce StatefulRedisConnection (StringCodec 기반)
 */
internal class LettuceCandidateRegistry(
    private val connection: StatefulRedisConnection<String, String>,
) {
    companion object: KLogging() {
        /** Redis SCAN 페이지 크기 힌트. 상한이 아니라 페이지당 반환 키 개수 가이드입니다. */
        private const val SCAN_PAGE_SIZE = 1000L
    }

    private val sync = connection.sync()

    private fun candidateKey(lockName: String, nodeId: String) =
        "leader:strategy:candidates:$lockName:$nodeId"

    private fun keyPattern(lockName: String) =
        "leader:strategy:candidates:$lockName:*"

    private fun validateLockName(lockName: String) {
        lockName.requireNotBlank("lockName")
        require(lockName.none { it in "*?[]\\" }) { "lockName must not contain SCAN glob metacharacters: $lockName" }
    }

    /**
     * 후보를 등록하거나 갱신합니다.
     *
     * [ttl] = [Duration.ZERO] 이면 TTL 없이 영구 저장합니다.
     * 분산 환경에서는 heartbeat 주기의 2배 이상으로 설정 권장.
     */
    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        validateLockName(lockName)
        val key = candidateKey(lockName, info.nodeId)
        val value = LettuceCandidateInfoCodec.encode(info)
        if (ttl == Duration.ZERO) sync.set(key, value)
        else sync.psetex(key, ttl.inWholeMilliseconds, value)
    }

    /** 후보를 등록 해제합니다. 존재하지 않는 nodeId 는 무시됩니다. */
    fun unregisterCandidate(lockName: String, nodeId: String) {
        validateLockName(lockName)
        sync.del(candidateKey(lockName, nodeId))
    }

    /**
     * [lockName] 에 등록된 현재 후보 목록을 반환합니다.
     *
     * 손상된 단일 항목(잘못된 인코딩, 숫자 파싱 실패 등)은 경고 로그를 남기고 skip 합니다.
     * 한 항목 때문에 전체 선출 라운드가 중단되지 않도록 보호합니다.
     */
    fun listCandidates(lockName: String): List<CandidateInfo> {
        validateLockName(lockName)
        val keys = scanKeys(lockName)
        if (keys.isEmpty()) return emptyList()
        return sync.mget(*keys.toTypedArray())
            .mapNotNull { kv ->
                val raw = if (kv.hasValue()) kv.value else return@mapNotNull null
                runCatching { LettuceCandidateInfoCodec.decode(raw) }
                    .onFailure { log.warn(it) { "[$lockName] CandidateInfo 디코딩 실패 — 항목 skip: key=${kv.key}" } }
                    .getOrNull()
            }
    }

    /**
     * 작업 결과를 후보 정보에 반영합니다.
     *
     * `SET key value XX KEEPTTL` 로 만료된 키의 좀비 복원을 방지합니다.
     * 키가 이미 만료된 경우 XX 플래그로 no-op 처리됩니다.
     */
    fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        validateLockName(lockName)
        val key = candidateKey(lockName, nodeId)
        val current = sync.get(key)?.let { LettuceCandidateInfoCodec.decode(it) } ?: return
        val updated = LettuceCandidateInfoCodec.encode(current.withResult(result))
        sync.set(key, updated, SetArgs.Builder.xx().keepttl())
    }

    private fun scanKeys(lockName: String): List<String> {
        val args = ScanArgs.Builder.matches(keyPattern(lockName)).limit(SCAN_PAGE_SIZE)
        val keys = mutableListOf<String>()
        var cursor: ScanCursor = ScanCursor.INITIAL
        do {
            val result = sync.scan(cursor, args)
            keys += result.keys
            cursor = result
        } while (!cursor.isFinished)
        return keys
    }
}
