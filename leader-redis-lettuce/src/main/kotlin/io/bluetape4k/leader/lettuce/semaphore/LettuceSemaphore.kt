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
import io.lettuce.core.api.sync.RedisCommands
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Lettuce Redis 클라이언트를 이용한 분산 세마포어(Distributed Semaphore) 구현체입니다.
 *
 * Redis의 카운터(잔여 허가 수)를 사용하여 세마포어를 구현합니다.
 * Lua 스크립트를 통해 acquire/release를 원자적으로 처리합니다.
 *
 * ```kotlin
 * val semaphore = LettuceSemaphore(connection, "my-semaphore", totalPermits = 3)
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
class LettuceSemaphore(
    private val connection: StatefulRedisConnection<String, String>,
    val semaphoreKey: String,
    val totalPermits: Int,
) {
    companion object: KLogging() {
        private const val RETRY_DELAY_MS = 50L
        private const val RETRY_DELAY_NANOS = RETRY_DELAY_MS * 1_000_000L

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

    private val syncCommands: RedisCommands<String, String> = connection.sync()
    private val asyncCommands: RedisAsyncCommands<String, String> = connection.async()

    init {
        totalPermits.requirePositiveNumber("totalPermits")
    }

    fun initialize() {
        val args = SetArgs().nx()
        syncCommands.set(semaphoreKey, totalPermits.toString(), args)
        log.debug { "세마포어 초기화: semaphoreKey=$semaphoreKey, totalPermits=$totalPermits" }
    }

    fun trySetPermits(permits: Int) {
        permits.requirePositiveNumber("permits")
        syncCommands.set(semaphoreKey, permits.toString())
        log.debug { "세마포어 허가 수 설정: semaphoreKey=$semaphoreKey, permits=$permits" }
    }

    fun availablePermits(): Int =
        syncCommands.get(semaphoreKey)?.toIntOrNull() ?: 0

    // =========================================================================
    // 동기 API
    // =========================================================================

    fun tryAcquire(permits: Int = 1): Boolean {
        permits.requirePositiveNumber("permits")

        val result = RedisScriptRunner.run<Long>(
            syncCommands, ACQUIRE_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(semaphoreKey), permits.toString()
        )
        val acquired = result >= 0
        log.debug { "Semaphore tryAcquire: key=$semaphoreKey, permits=$permits, acquired=$acquired" }
        return acquired
    }

    fun acquire(permits: Int = 1, waitTime: Duration = 30.seconds) {
        permits.requirePositiveNumber("permits")

        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (tryAcquire(permits)) return
            LockSupport.parkNanos(RETRY_DELAY_NANOS)
        }
        throw IllegalStateException("세마포어 획득 시간 초과: semaphoreKey=$semaphoreKey, permits=${permits}")
    }

    fun release(permits: Int = 1) {
        permits.requirePositiveNumber("permits")

        val remaining = RedisScriptRunner.run<Long>(
            syncCommands, RELEASE_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(semaphoreKey), permits.toString(), totalPermits.toString()
        )
        log.debug { "Semaphore release: key=$semaphoreKey, permits=$permits, remaining=$remaining" }
    }

    // =========================================================================
    // 비동기 API (CompletableFuture)
    // =========================================================================

    fun tryAcquireAsync(permits: Int = 1): CompletableFuture<Boolean> {
        permits.requirePositiveNumber("permits")

        return RedisScriptRunner.runAsync<Long>(
            asyncCommands, ACQUIRE_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(semaphoreKey), permits.toString()
        ).thenApply { result ->
            val acquired = result >= 0
            log.debug { "Semaphore tryAcquireAsync: key=$semaphoreKey, permits=$permits, acquired=$acquired" }
            acquired
        }
    }

    fun acquireAsync(permits: Int = 1, waitTime: Duration = 30.seconds): CompletableFuture<Unit> {
        permits.requirePositiveNumber("permits")
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds

        fun attempt(): CompletableFuture<Unit> =
            tryAcquireAsync(permits).thenCompose { acquired ->
                if (acquired) {
                    CompletableFuture.completedFuture(Unit)
                } else if (System.currentTimeMillis() < deadline) {
                    val delayed = CompletableFuture.delayedExecutor(RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                    CompletableFuture.runAsync({}, delayed).thenCompose { attempt() }
                } else {
                    CompletableFuture.failedFuture(
                        IllegalStateException("세마포어 획득 시간 초과 (async): semaphoreKey=$semaphoreKey")
                    )
                }
            }

        return attempt()
    }

    fun releaseAsync(permits: Int = 1): CompletableFuture<Unit> {
        permits.requirePositiveNumber("permits")

        return RedisScriptRunner.runAsync<Long>(
            asyncCommands, RELEASE_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(semaphoreKey), permits.toString(), totalPermits.toString()
        ).thenApply { remaining ->
            log.debug { "Semaphore releaseAsync: key=$semaphoreKey, permits=$permits, remaining=$remaining" }
        }
    }
}
