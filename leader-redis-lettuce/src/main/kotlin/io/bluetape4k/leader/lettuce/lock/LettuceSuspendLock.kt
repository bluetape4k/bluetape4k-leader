package io.bluetape4k.leader.lettuce.lock

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.remainingMinLeaseTime
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import java.time.Instant
import kotlin.coroutines.coroutineContext
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
            """
if redis.call('get', KEYS[1]) == ARGV[1] then
  local ttl = tonumber(ARGV[2])
  if ttl and ttl > 0 then
    return redis.call('pexpire', KEYS[1], ttl)
  end
  return redis.call('del', KEYS[1])
else
  return 0
end"""
        )

        private val EXTEND_SCRIPT = RedisScript(
            """
if redis.call('get', KEYS[1]) == ARGV[1] then
  return redis.call('pexpire', KEYS[1], ARGV[2])
else
  return 0
end"""
        )
    }

    private val tokenRef = atomic<String?>(null)

    private val asyncCommands: RedisAsyncCommands<String, String> = connection.async()

    suspend fun isLocked(): Boolean = asyncCommands.get(lockKey).await() != null

    suspend fun isHeldByCurrentInstance(): Boolean {
        val token = tokenRef.value ?: return false
        return asyncCommands.get(lockKey).await() == token
    }

    /**
     * 현재 lock 토큰을 반환합니다 (acquire 후, unlock 전).
     *
     * Backend module elector 가 [io.bluetape4k.leader.LeaderLockHandle.Real.token] 에 주입할 값으로 사용.
     * 미보유 시 `null`.
     */
    fun currentToken(): String? = tokenRef.value

    suspend fun tryLock(
        waitTime: Duration = Duration.ZERO,
        leaseTime: Duration = defaultLeaseTime,
    ): Boolean {
        // Token generation uses SecureRandom for ≥128-bit entropy (see #50 spec §1-3)
        val token = Base58.randomString(length = 22)
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
        // Token generation uses SecureRandom for ≥128-bit entropy (see #50 spec §1-3)
        val token = Base58.randomString(length = 22)
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

    suspend fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) {
        val token =
            tokenRef.getAndSet(null)
                ?: throw IllegalStateException("현재 인스턴스가 락을 보유하지 않습니다: lockKey=$lockKey")
        val remainingMs = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime).inWholeMilliseconds

        val released = RedisScriptRunner.runSuspending<Long>(
            asyncCommands, UNLOCK_SCRIPT, ScriptOutputType.INTEGER, arrayOf(lockKey), token, remainingMs.toString()
        )

        check(released > 0L) {
            "Lock 해제 실패 (토큰 불일치 또는 만료, suspend): lockKey=$lockKey"
        }
        log.debug { "Lock 해제 성공 (suspend): lockKey=$lockKey" }
    }

    /**
     * Lua atomic extend — token guard + PEXPIRE (suspend variant).
     *
     * 자세한 분류 결과가 필요하면 [extendDetailed] 사용.
     */
    suspend fun extend(leaseTime: Duration = defaultLeaseTime): Boolean =
        extendDetailed(leaseTime).isExtended

    /**
     * Lua atomic extend — [ExtendOutcome] 반환 (T7 PR 2, suspend variant).
     *
     * ## 동작/계약
     * - Lettuce `asyncCommands` 는 Netty event-loop 기반 non-blocking 이지만, R9 권고에 따라
     *   suspend 진입점은 `coroutineContext.ensureActive()` 로 cancellation 을 명시적으로 확인.
     * - token 미보유 → [ExtendOutcome.NotHeld]
     * - script 결과 `1` → [ExtendOutcome.Extended] (`observedExpireAt = Instant.now() + leaseTime` best-effort)
     * - script 결과 `0` → [ExtendOutcome.NotHeld]
     */
    suspend fun extendDetailed(leaseTime: Duration = defaultLeaseTime): ExtendOutcome {
        coroutineContext.ensureActive()
        val token = tokenRef.value ?: return ExtendOutcome.NotHeld
        val leaseMs = leaseTime.inWholeMilliseconds

        val extended = RedisScriptRunner.runSuspending<Long>(
            asyncCommands, EXTEND_SCRIPT, ScriptOutputType.INTEGER, arrayOf(lockKey), token, leaseMs.toString()
        )
        return if (extended > 0L) {
            ExtendOutcome.Extended(Instant.now().plusMillis(leaseMs))
        } else {
            ExtendOutcome.NotHeld
        }
    }
}
