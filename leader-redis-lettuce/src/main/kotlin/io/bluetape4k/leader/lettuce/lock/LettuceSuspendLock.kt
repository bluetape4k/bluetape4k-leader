package io.bluetape4k.leader.lettuce.lock

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.lettuce.script.RedisScript
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Lettuce Redis 클라이언트를 이용한 분산 락의 코루틴 구현체입니다.
 *
 * `SET NX PX` + Lua 스크립트를 통해 원자적 락 획득/해제를 suspend 함수로 제공합니다.
 *
 * ```kotlin
 * val lock = LettuceSuspendLock(connection, "my-lock")
 *
 * if (lock.tryLock()) {
 *     try { doWork() } finally { lock.unlock() }
 * }
 * ```
 *
 * @param connection Lettuce StatefulRedisConnection (StringCodec 기반)
 * @param lockKey Redis에 저장될 락 키
 * @param defaultLeaseTime 락 유지 시간 기본값 (기본 30초)
 */
class LettuceSuspendLock(
    private val connection: StatefulRedisConnection<String, String>,
    val lockKey: String,
    val defaultLeaseTime: Duration = 30.seconds,
) {
    companion object: KLoggingChannel() {
        private const val RETRY_DELAY_MS = 50L
        private const val DEFAULT_MAX_WAIT_MINUTES = 5L

        private val UNLOCK_SCRIPT = RedisScript(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
        )
    }

    private val tokenRef = atomic<String?>(null)

    private val asyncCommands: RedisAsyncCommands<String, String> = connection.async()

    suspend fun isLocked(): Boolean = asyncCommands.get(lockKey).await() != null

    suspend fun isHeldByCurrentInstance(): Boolean {
        val token = tokenRef.value ?: return false
        return asyncCommands.get(lockKey).await() == token
    }

    suspend fun tryLock(
        waitTime: Duration = Duration.ZERO,
        leaseTime: Duration = defaultLeaseTime,
    ): Boolean {
        val token = Base58.randomString(length = 8)
        val leaseMs = leaseTime.inWholeMilliseconds
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds

        do {
            val args = SetArgs().nx().px(leaseMs)
            val result = asyncCommands.set(lockKey, token, args).await()
            if (result != null) {
                tokenRef.value = token
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

    suspend fun lock(
        leaseTime: Duration = defaultLeaseTime,
        maxWaitTime: Duration = DEFAULT_MAX_WAIT_MINUTES.minutes,
    ) {
        val token = Base58.randomString(length = 8)
        val leaseMs = leaseTime.inWholeMilliseconds
        val deadline = System.currentTimeMillis() + maxWaitTime.inWholeMilliseconds

        while (true) {
            val args = SetArgs().nx().px(leaseMs)
            val result = asyncCommands.set(lockKey, token, args).await()
            if (result != null) {
                tokenRef.value = token
                log.debug { "Lock 획득 성공 (suspend): lockKey=$lockKey" }
                return
            }
            check(System.currentTimeMillis() < deadline) {
                "Lock 획득 시간 초과 (suspend): lockKey=$lockKey, maxWaitTime=$maxWaitTime"
            }
            delay(RETRY_DELAY_MS.milliseconds)
        }
    }

    suspend fun unlock() {
        val token =
            tokenRef.getAndSet(null)
                ?: throw IllegalStateException("현재 인스턴스가 락을 보유하지 않습니다: lockKey=$lockKey")

        val released = RedisScriptRunner.runSuspending<Long>(
            asyncCommands, UNLOCK_SCRIPT, ScriptOutputType.INTEGER, arrayOf(lockKey), token
        )

        check(released > 0L) {
            "Lock 해제 실패 (토큰 불일치 또는 만료, suspend): lockKey=$lockKey"
        }
        log.debug { "Lock 해제 성공 (suspend): lockKey=$lockKey" }
    }
}
