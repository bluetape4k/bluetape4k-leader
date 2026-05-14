package io.bluetape4k.leader.lettuce.lock

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.leader.lettuce.script.RedisScript
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.atomicfu.atomic
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Lettuce Redis 클라이언트를 이용한 분산 락(Distributed Lock) 구현체입니다.
 *
 * `SET NX PX` + Lua 스크립트 기반 비재진입(non-reentrant) 분산 뮤텍스입니다.
 * 락 토큰으로 UUID를 사용하여 스레드/코루틴에 독립적으로 동작합니다.
 *
 * ```kotlin
 * val lock = LettuceLock(connection, "my-lock")
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
class LettuceLock(
    private val connection: StatefulRedisConnection<String, String>,
    val lockKey: String,
    val defaultLeaseTime: Duration = 30.seconds,
) {
    companion object: KLogging() {
        private const val RETRY_DELAY_MS = 50L
        private const val RETRY_DELAY_NANOS = RETRY_DELAY_MS * 1_000_000L

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

    private val syncCommands: RedisCommands<String, String> = connection.sync()
    private val asyncCommands: RedisAsyncCommands<String, String> = connection.async()

    fun isLocked(): Boolean = syncCommands.get(lockKey) != null

    fun isHeldByCurrentInstance(): Boolean {
        val token = tokenRef.value ?: return false
        return syncCommands.get(lockKey) == token
    }

    /**
     * 현재 lock 토큰을 반환합니다 (acquire 후, unlock 전).
     *
     * Backend module elector 가 [io.bluetape4k.leader.LeaderLockHandle.Real.token] 에 주입할 값으로 사용.
     * 미보유 시 `null`.
     */
    fun currentToken(): String? = tokenRef.value

    // =========================================================================
    // 동기 API
    // =========================================================================

    fun tryLock(
        waitTime: Duration = Duration.ZERO,
        leaseTime: Duration = defaultLeaseTime,
    ): Boolean {
        // Token generation uses SecureRandom for ≥128-bit entropy (see #50 spec §1-3)
        val token = Base58.randomString(22)
        val leaseMs = leaseTime.inWholeMilliseconds
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds

        do {
            val args = SetArgs().nx().px(leaseMs)
            val result = syncCommands.set(lockKey, token, args)
            if (result != null) {
                tokenRef.value = token
                log.debug { "Lock 획득 성공: lockKey=$lockKey" }
                return true
            }
            if (System.currentTimeMillis() < deadline) {
                LockSupport.parkNanos(RETRY_DELAY_NANOS)
            }
        } while (System.currentTimeMillis() < deadline)

        log.debug { "Lock 획득 실패 (timeout): lockKey=$lockKey" }
        return false
    }

    fun lock(leaseTime: Duration = defaultLeaseTime, maxWaitTime: Duration = 5.minutes) {
        // Token generation uses SecureRandom for ≥128-bit entropy (see #50 spec §1-3)
        val token = Base58.randomString(length = 22)
        val leaseMs = leaseTime.inWholeMilliseconds
        val args = SetArgs().nx().px(leaseMs)
        val deadline = System.nanoTime() + maxWaitTime.inWholeNanoseconds

        while (true) {
            val result = syncCommands.set(lockKey, token, args)
            if (result != null) {
                tokenRef.value = token
                log.debug { "Lock 획득 성공: lockKey=$lockKey" }
                return
            }
            check(System.nanoTime() <= deadline) {
                "Lock 획득 시간 초과: lockKey=$lockKey, maxWaitTime=$maxWaitTime"
            }
            LockSupport.parkNanos(RETRY_DELAY_NANOS)
        }
    }

    fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) {
        val token = tokenRef.getAndSet(null)
            ?: throw IllegalStateException("현재 인스턴스가 락을 보유하지 않습니다: lockKey=$lockKey")
        val remainingMs = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime).inWholeMilliseconds

        val released = RedisScriptRunner.run<Long>(
            syncCommands, UNLOCK_SCRIPT, ScriptOutputType.INTEGER, arrayOf(lockKey), token, remainingMs.toString()
        )
        check(released > 0L) {
            "Lock 해제 실패 (토큰 불일치 또는 만료): lockKey=$lockKey"
        }
        log.debug { "Lock 해제 성공: lockKey=$lockKey" }
    }

    /**
     * Lua atomic extend — token guard + PEXPIRE.
     *
     * ## 동작/계약
     * - token 미보유 ([tokenRef] null) → `false`
     * - script 결과 `1` → `true` (PEXPIRE 성공)
     * - script 결과 `0` → `false` (token mismatch / lease 만료)
     *
     * 자세한 분류 결과가 필요하면 [extendDetailed] 사용.
     */
    fun extend(leaseTime: Duration = defaultLeaseTime): Boolean =
        extendDetailed(leaseTime).isExtended

    /**
     * Lua atomic extend — [ExtendOutcome] 반환 (T7 PR 2).
     *
     * ## 동작/계약
     * - token 미보유 → [ExtendOutcome.NotHeld]
     * - script 결과 `1` → [ExtendOutcome.Extended] (`observedExpireAt = Instant.now() + leaseTime` best-effort)
     * - script 결과 `0` → [ExtendOutcome.NotHeld] (token mismatch / lease 만료)
     *
     * **caller (`LettuceLockExtendDelegate`) 가 try/catch 로 backend exception → [ExtendOutcome.BackendError] 변환**.
     * 이 메서드는 backend exception 을 propagate.
     */
    fun extendDetailed(leaseTime: Duration = defaultLeaseTime): ExtendOutcome {
        val token = tokenRef.value ?: return ExtendOutcome.NotHeld
        val leaseMs = leaseTime.inWholeMilliseconds

        val extended = RedisScriptRunner.run<Long>(
            syncCommands, EXTEND_SCRIPT, ScriptOutputType.INTEGER, arrayOf(lockKey), token, leaseMs.toString()
        )
        return if (extended > 0L) {
            ExtendOutcome.Extended(Instant.now().plusMillis(leaseMs))
        } else {
            ExtendOutcome.NotHeld
        }
    }

    // =========================================================================
    // 비동기 API (CompletableFuture)
    // =========================================================================

    fun tryLockAsync(
        waitTime: Duration = Duration.ZERO,
        leaseTime: Duration = defaultLeaseTime,
    ): CompletableFuture<Boolean> {
        // Token generation uses SecureRandom for ≥128-bit entropy (see #50 spec §1-3)
        val token = Base58.randomString(length = 22)
        val leaseMs = leaseTime.inWholeMilliseconds
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds

        fun attempt(): CompletableFuture<Boolean> {
            val args = SetArgs().nx().px(leaseMs)
            return asyncCommands.set(lockKey, token, args).toCompletableFuture()
                .thenCompose { result ->
                    if (result != null) {
                        tokenRef.value = token
                        log.debug { "Lock 획득 성공 (async): lockKey=$lockKey" }
                        CompletableFuture.completedFuture(true)
                    } else if (System.currentTimeMillis() < deadline) {
                        val delayed = CompletableFuture.delayedExecutor(RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                        CompletableFuture.runAsync({}, delayed).thenCompose { attempt() }
                    } else {
                        log.debug { "Lock 획득 실패 (timeout, async): lockKey=$lockKey" }
                        CompletableFuture.completedFuture(false)
                    }
                }
        }

        return attempt()
    }

    fun lockAsync(
        leaseTime: Duration = defaultLeaseTime,
        maxWaitTime: Duration = 5.minutes,
    ): CompletableFuture<Unit> {
        // Token generation uses SecureRandom for ≥128-bit entropy (see #50 spec §1-3)
        val token = Base58.randomString(length = 22)
        val leaseMs = leaseTime.inWholeMilliseconds
        val deadline = System.currentTimeMillis() + maxWaitTime.inWholeMilliseconds

        fun attempt(): CompletableFuture<Unit> {
            val args = SetArgs().nx().px(leaseMs)
            return asyncCommands.set(lockKey, token, args).toCompletableFuture()
                .thenCompose { result ->
                    if (result != null) {
                        tokenRef.value = token
                        log.debug { "Lock 획득 성공 (async): lockKey=$lockKey" }
                        CompletableFuture.completedFuture(Unit)
                    } else if (System.currentTimeMillis() < deadline) {
                        val delayed = CompletableFuture.delayedExecutor(RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                        CompletableFuture.runAsync({}, delayed).thenCompose { attempt() }
                    } else {
                        CompletableFuture.failedFuture(
                            IllegalStateException("Lock 획득 시간 초과 (async): lockKey=$lockKey")
                        )
                    }
                }
        }

        return attempt()
    }

    fun unlockAsync(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ): CompletableFuture<Unit> {
        val token = tokenRef.getAndSet(null)
            ?: return CompletableFuture.failedFuture(
                IllegalStateException("현재 인스턴스가 락을 보유하지 않습니다: lockKey=$lockKey")
            )
        val remainingMs = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime).inWholeMilliseconds

        return RedisScriptRunner.runAsync<Long>(
            asyncCommands, UNLOCK_SCRIPT, ScriptOutputType.INTEGER, arrayOf(lockKey), token, remainingMs.toString()
        ).thenApply { released ->
            check(released > 0L) {
                "Lock 해제 실패 (토큰 불일치 또는 만료, async): lockKey=$lockKey"
            }
            log.debug { "Lock 해제 성공 (async): lockKey=$lockKey" }
        }
    }

    fun extendAsync(leaseTime: Duration = defaultLeaseTime): CompletableFuture<Boolean> {
        val token = tokenRef.value ?: return CompletableFuture.completedFuture(false)
        val leaseMs = leaseTime.inWholeMilliseconds

        return RedisScriptRunner.runAsync<Long>(
            asyncCommands, EXTEND_SCRIPT, ScriptOutputType.INTEGER, arrayOf(lockKey), token, leaseMs.toString()
        ).thenApply { it > 0L }
    }
}
