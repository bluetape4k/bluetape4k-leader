package io.bluetape4k.leader.hazelcast.lock

import com.hazelcast.map.IMap
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * [IMap] 기반 분산 락의 코루틴 구현체입니다.
 *
 * `putIfAbsent(key, token, ttl)` / `remove(key, token)` 을 `withContext(Dispatchers.IO)` 로 감싸
 * suspend 함수로 제공합니다. 토큰 기반이므로 코루틴 스레드 전환과 무관하게 안전합니다.
 *
 * @param lockMap 락 상태를 저장하는 [IMap]
 * @param lockKey 락 식별 키
 */
class HazelcastSuspendLock(
    private val lockMap: IMap<String, String>,
    val lockKey: String,
) {
    companion object: KLoggingChannel() {
        private const val RETRY_DELAY_MS = 50L
    }

    private val token: String = UUID.randomUUID().toString()

    /**
     * [waitTime] 내에 락 획득을 시도합니다. 성공하면 `true`, 타임아웃이면 `false`를 반환합니다.
     *
     * 블로킹 `putIfAbsent` 를 `Dispatchers.IO` 에서 실행하고, 재시도 대기는 `delay` 로 suspend 합니다.
     */
    suspend fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = System.currentTimeMillis() + waitTime.toMillis()
        val leaseMs = leaseTime.toMillis()

        do {
            val previous = withContext(Dispatchers.IO) {
                lockMap.putIfAbsent(lockKey, token, leaseMs, TimeUnit.MILLISECONDS)
            }
            if (previous == null) {
                log.debug { "Lock 획득 성공 (suspend): lockKey=$lockKey" }
                return true
            }
            if (System.currentTimeMillis() < deadline) {
                delay(RETRY_DELAY_MS.milliseconds)
            }
        } while (System.currentTimeMillis() < deadline)

        log.debug { "Lock 획득 실패 (timeout, suspend): lockKey=$lockKey" }
        return false
    }

    suspend fun isHeldByCurrentInstance(): Boolean = withContext(Dispatchers.IO) {
        lockMap[lockKey] == token
    }

    suspend fun unlock() = withContext(Dispatchers.IO) {
        val removed = lockMap.remove(lockKey, token)
        if (removed) {
            log.debug { "Lock 해제 성공 (suspend): lockKey=$lockKey" }
        }
    }
}
