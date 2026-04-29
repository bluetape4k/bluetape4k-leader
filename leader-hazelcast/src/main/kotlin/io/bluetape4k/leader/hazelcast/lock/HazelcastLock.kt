package io.bluetape4k.leader.hazelcast.lock

import com.hazelcast.map.IMap
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * [IMap] 기반 분산 락 구현체입니다.
 *
 * `putIfAbsent(key, token, ttl)` 으로 원자적 획득, `remove(key, token)` 으로 토큰 일치 시에만 해제합니다.
 * 스레드 ID 에 의존하지 않으므로 Virtual Thread, ThreadPool 등 어느 실행 모델에서도 안전합니다.
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

    private val token: String = UUID.randomUUID().toString()

    /**
     * [waitTime] 내에 락 획득을 시도합니다. 성공하면 `true`, 타임아웃이면 `false`를 반환합니다.
     */
    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = System.currentTimeMillis() + waitTime.toMillis()
        val leaseMs = leaseTime.toMillis()

        do {
            val previous = lockMap.putIfAbsent(lockKey, token, leaseMs, TimeUnit.MILLISECONDS)
            if (previous == null) {
                log.debug { "Lock 획득 성공: lockKey=$lockKey" }
                return true
            }
            if (System.currentTimeMillis() < deadline) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        } while (System.currentTimeMillis() < deadline)

        log.debug { "Lock 획득 실패 (timeout): lockKey=$lockKey" }
        return false
    }

    fun isHeldByCurrentInstance(): Boolean = lockMap[lockKey] == token

    fun unlock() {
        val removed = lockMap.remove(lockKey, token)
        if (removed) {
            log.debug { "Lock 해제 성공: lockKey=$lockKey" }
        }
    }
}
