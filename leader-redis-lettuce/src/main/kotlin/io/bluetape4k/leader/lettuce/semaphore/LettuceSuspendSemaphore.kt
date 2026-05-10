package io.bluetape4k.leader.lettuce.semaphore

import io.bluetape4k.leader.lettuce.script.RedisScript
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * Lettuce Redis 클라이언트를 이용한 분산 세마포어의 코루틴(suspend) 구현체입니다.
 *
 * [LettuceSemaphore]의 코루틴 버전으로, Redis의 카운터(잔여 허가 수)를 사용하여 세마포어를 구현합니다.
 *
 * ```kotlin
 * val semaphore = LettuceSuspendSemaphore(connection, "my-semaphore", totalPermits = 3)
 * semaphore.initialize()
 *
 * if (semaphore.tryAcquire()) {
 *     try { doWork() } finally { semaphore.release() }
 * }
 * ```
 *
 * @param connection Lettuce StatefulRedisConnection (StringCodec 기반)
 * @param semaphoreKey Redis에 저장될 세마포어 키
 * @param totalPermits 전체 허가 수
 */
@Deprecated(
    "Replaced by LettuceSlotTokenGroup which supports slot-token TTL and crash recovery",
    level = DeprecationLevel.WARNING,
)
class LettuceSuspendSemaphore(
    private val connection: StatefulRedisConnection<String, String>,
    val semaphoreKey: String,
    val totalPermits: Int,
) {
    companion object: KLogging() {
        private const val RETRY_DELAY_MS = 50L

        private val ACQUIRE_SCRIPT = RedisScript(
            """
local v = tonumber(redis.call('get', KEYS[1]))
if v and v >= tonumber(ARGV[1]) then
  return redis.call('decrby', KEYS[1], ARGV[1])
else
  return -1
end"""
        )

        private val RELEASE_SCRIPT = RedisScript(
            """
local v = tonumber(redis.call('incrby', KEYS[1], ARGV[1]))
if v > tonumber(ARGV[2]) then
  redis.call('set', KEYS[1], ARGV[2])
  return tonumber(ARGV[2])
end
return v"""
        )
    }

    private val asyncCommands: RedisAsyncCommands<String, String> = connection.async()

    init {
        totalPermits.requirePositiveNumber("totalPermits")
    }

    suspend fun initialize() {
        asyncCommands.set(semaphoreKey, totalPermits.toString(), SetArgs().nx()).await()
        log.debug { "세마포어 초기화: semaphoreKey=$semaphoreKey, totalPermits=$totalPermits" }
    }

    suspend fun trySetPermits(permits: Int) {
        permits.requirePositiveNumber("permits")
        asyncCommands.set(semaphoreKey, permits.toString()).await()
        log.debug { "세마포어 허가 수 설정: semaphoreKey=$semaphoreKey, permits=$permits" }
    }

    suspend fun availablePermits(): Int =
        asyncCommands.get(semaphoreKey).await()?.toIntOrNull() ?: 0

    // =========================================================================
    // 코루틴 API (suspend)
    // =========================================================================

    suspend fun tryAcquire(permits: Int = 1): Boolean {
        permits.requirePositiveNumber("permits")

        val result = RedisScriptRunner.runSuspending<Long>(
            asyncCommands, ACQUIRE_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(semaphoreKey), permits.toString()
        )
        val acquired = result >= 0
        log.debug { "Semaphore tryAcquire: key=$semaphoreKey, permits=$permits, acquired=$acquired" }
        return acquired
    }

    suspend fun acquire(permits: Int = 1, waitTime: Duration = 30.seconds) {
        permits.requirePositiveNumber("permits")

        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (tryAcquire(permits)) return
            delay(RETRY_DELAY_MS.milliseconds)
        }
        throw IllegalStateException("세마포어 획득 시간 초과 (suspend): semaphoreKey=$semaphoreKey, permits=$permits")
    }

    suspend fun release(permits: Int = 1) {
        permits.requirePositiveNumber("permits")

        val remaining = RedisScriptRunner.runSuspending<Long>(
            asyncCommands, RELEASE_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(semaphoreKey), permits.toString(), totalPermits.toString()
        )
        log.debug { "Semaphore release: key=$semaphoreKey, permits=$permits, remaining=$remaining" }
    }
}
