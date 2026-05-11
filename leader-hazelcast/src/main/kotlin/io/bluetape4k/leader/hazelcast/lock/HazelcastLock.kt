package io.bluetape4k.leader.hazelcast.lock

import com.hazelcast.core.HazelcastException
import com.hazelcast.map.IMap
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * [IMap] 기반 분산 락 구현체입니다.
 *
 * `putIfAbsent(key, token, ttl)` 으로 원자적 획득, `remove(key, token)` 으로 토큰 일치 시에만 해제합니다.
 * 스레드 ID 에 의존하지 않으므로 Virtual Thread, ThreadPool 등 어느 실행 모델에서도 안전합니다.
 *
 * **주의:** [leaseTime]은 action의 최대 실행 시간보다 충분히 커야 합니다.
 * TTL이 만료되면 락이 자동 해제되어 다른 노드가 동시에 리더가 될 수 있습니다.
 *
 * **주의:** [lockMap]에는 near-cache를 절대 활성화하지 마십시오.
 * near-cache의 stale 값이 [isHeldByCurrentInstance] 오판을 유발할 수 있습니다.
 *
 * @param lockMap 락 상태를 저장하는 [IMap]
 * @param lockKey 락 식별 키
 */
class HazelcastLock(
    private val lockMap: IMap<String, String>,
    val lockKey: String,
) {
    companion object: KLogging() {
        private const val RETRY_DELAY_MS = 50L
    }

    private val token: String = Base58.randomString(8)

    /**
     * [waitTime] 내에 락 획득을 시도합니다. 성공하면 `true`, 타임아웃이거나 클러스터 오류이면 `false`를 반환합니다.
     *
     * Hazelcast 클러스터 이벤트(파티션 마이그레이션, 멤버 이탈 등)로 인한 [HazelcastException]은
     * 예외를 전파하지 않고 `false`로 처리합니다. 이로써 `runIfLeader()` 가 절대 throws하지 않는
     * 계약을 보장합니다.
     */
    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds
        val leaseMs = leaseTime.inWholeMilliseconds

        do {
            val previous = try {
                lockMap.putIfAbsent(lockKey, token, leaseMs, TimeUnit.MILLISECONDS)
            } catch (e: HazelcastException) {
                log.warn(e) { "Hazelcast 클러스터 오류로 락 획득 실패: lockKey=$lockKey" }
                return false
            }
            if (previous == null) {
                log.debug { "Lock 획득 성공: lockKey=$lockKey" }
                return true
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0) {
                Thread.sleep(minOf(RETRY_DELAY_MS, remaining))
            }
        } while (System.currentTimeMillis() < deadline)

        log.debug { "Lock 획득 실패 (timeout): lockKey=$lockKey" }
        return false
    }

    /**
     * 현재 인스턴스(토큰)가 락을 보유하고 있는지 확인합니다.
     *
     * [IMap]에 저장된 값이 이 인스턴스의 토큰과 일치하는 경우에만 `true`를 반환합니다.
     */
    fun isHeldByCurrentInstance(): Boolean = lockMap[lockKey] == token

    /**
     * 현재 인스턴스가 보유한 락을 해제합니다.
     *
     * 토큰 일치 여부를 검증한 후 원자적으로 제거합니다.
     * 토큰 불일치(리스 만료로 인한 타 노드 재획득 등)인 경우 경고 로그를 남깁니다.
     */
    fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) {
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)
        val released = if (remaining > Duration.ZERO) {
            if (isHeldByCurrentInstance()) {
                lockMap.set(lockKey, token, remaining.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                true
            } else {
                false
            }
        } else {
            lockMap.remove(lockKey, token)
        }
        if (released) {
            log.debug { "Lock 해제 성공: lockKey=$lockKey" }
        } else {
            log.warn { "Lock 해제 실패 — 토큰 불일치 (리스 만료 가능성). lockKey=$lockKey" }
        }
    }

    /**
     * 락의 TTL 을 [leaseTime] 만큼 token-guarded 로 연장하고 [ExtendOutcome] 을 반환합니다 — T12 PR 7 (Issue #79).
     *
     * ## 동작/계약
     * - 1단계: [IMap.replace] (CAS) 로 entry value 가 우리 토큰과 일치하는지 atomic 검증
     * - 2단계: 토큰 일치 시 [IMap.setTtl] 로 TTL 갱신
     * - 토큰 불일치 / 미보유 → [ExtendOutcome.NotHeld]
     * - 갱신 성공 → [ExtendOutcome.Extended] (`observedExpireAt = now + leaseTime` — best-effort)
     * - [HazelcastException] → [ExtendOutcome.BackendError]
     *
     * ## R6 — expired-entry revival 차단
     * Hazelcast IMap 은 TTL 만료 시 자동 evict 하므로 만료된 entry 는 [IMap.replace] 가 `false` 반환 → NotHeld.
     *
     * ## 설계 참고 — EntryProcessor 대신 replace+setTtl 사용 이유
     * Hazelcast client-server 모드에서 사용자 정의 `EntryProcessor` 는 server JVM 에 클래스 배포가 필요합니다
     * ([UserCodeDeployment] 또는 server 측 사전 배포). bluetape4k-testcontainers 의 Hazelcast 서버는
     * vanilla 이미지이므로 클래스를 찾을 수 없어 `HazelcastSerializationException(ClassNotFoundException)` 발생.
     * 대신 built-in atomic primitive ([IMap.replace] CAS + [IMap.setTtl]) 조합으로 동일한 token-guard 의미 보장.
     *
     * ## Race window (acceptable)
     * `replace` 와 `setTtl` 사이 sub-millisecond 시간 동안 entry 가 TTL 만료될 수 있습니다.
     * 이 경우 `setTtl` 이 `false` 반환 → NotHeld 로 처리.
     * 다른 인스턴스가 takeover 한 시점에 우리 `setTtl` 이 도착하면 그 인스턴스의 TTL 을 갱신하는
     * theoretical race 가 존재하지만, `replace` 가 token 검증을 통과한 직후의 매우 좁은 윈도우입니다.
     */
    fun extendDetailed(leaseTime: Duration): ExtendOutcome {
        val leaseMs = leaseTime.inWholeMilliseconds
        val nowMs = System.currentTimeMillis()
        return try {
            // 1) CAS — value 가 우리 토큰일 때만 (no-op replace) 성공
            val matched = lockMap.replace(lockKey, token, token)
            if (!matched) {
                log.debug { "Hazelcast extend NotHeld (token mismatch / 만료): lockKey=$lockKey" }
                ExtendOutcome.NotHeld
            } else {
                // 2) TTL 갱신
                val updated = lockMap.setTtl(lockKey, leaseMs, TimeUnit.MILLISECONDS)
                if (updated) {
                    ExtendOutcome.Extended(Instant.ofEpochMilli(nowMs + leaseMs))
                } else {
                    log.debug { "Hazelcast extend NotHeld (setTtl 실패 — race window): lockKey=$lockKey" }
                    ExtendOutcome.NotHeld
                }
            }
        } catch (e: HazelcastException) {
            ExtendOutcome.BackendError(e)
        }
    }
}
