package io.bluetape4k.leader.hazelcast.lock

import com.hazelcast.core.HazelcastException
import com.hazelcast.map.IMap
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
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
    fun unlock() {
        val removed = lockMap.remove(lockKey, token)
        if (removed) {
            log.debug { "Lock 해제 성공: lockKey=$lockKey" }
        } else {
            log.warn { "Lock 해제 실패 — 토큰 불일치 (리스 만료 가능성). lockKey=$lockKey" }
        }
    }
}
