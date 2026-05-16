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
 * Coroutine-based distributed lock implementation using the Lettuce Redis client.
 *
 * Provides atomic lock acquisition and release as suspend functions via `SET NX PX` and Lua scripts.
 *
 * ```kotlin
 * val lock = LettuceSuspendLock(connection, "my-lock")
 *
 * if (lock.tryLock()) {
 *     try { doWork() } finally { lock.unlock() }
 * }
 * ```
 *
 * @param connection Lettuce StatefulRedisConnection (StringCodec-based)
 * @param lockKey The Redis key under which the lock is stored
 * @param defaultLeaseTime Default lock lease duration (default: 30 seconds)
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
     * Returns the current lock token (after acquire, before unlock).
     *
     * Used by the backend module elector to inject into [io.bluetape4k.leader.LeaderLockHandle.Real.token].
     * Returns `null` if not currently held.
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
     * Use [extendDetailed] when a detailed classification result is needed.
     */
    suspend fun extend(leaseTime: Duration = defaultLeaseTime): Boolean =
        extendDetailed(leaseTime).isExtended

    /**
     * Lua atomic extend — returns [ExtendOutcome] (T7 PR 2, suspend variant).
     *
     * ## Behavior / Contract
     * - Although Lettuce `asyncCommands` is Netty event-loop-based non-blocking, the suspend entry point
     *   explicitly checks cancellation via `coroutineContext.ensureActive()` per R9 guidelines.
     * - Token not held → [ExtendOutcome.NotHeld]
     * - Script result `1` → [ExtendOutcome.Extended] (`observedExpireAt = Instant.now() + leaseTime`, best-effort)
     * - Script result `0` → [ExtendOutcome.NotHeld]
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
