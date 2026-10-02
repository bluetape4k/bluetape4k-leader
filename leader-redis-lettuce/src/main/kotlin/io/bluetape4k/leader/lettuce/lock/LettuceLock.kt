package io.bluetape4k.leader.lettuce.lock

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.lettuce.DEFAULT_TOKEN_LENGTH
import io.bluetape4k.leader.lettuce.DefaultLeaseTime
import io.bluetape4k.leader.lettuce.internal.MonotonicDeadline
import io.bluetape4k.leader.lettuce.script.RedisScript
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.atomicfu.atomic
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * `LettuceLock`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property connection Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockKey Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property defaultLeaseTime Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class LettuceLock(
    private val connection: StatefulRedisConnection<String, String>,
    val lockKey: String,
    val defaultLeaseTime: Duration = DefaultLeaseTime,
) {
    companion object: KLogging() {
        private const val RETRY_DELAY_MS = 50L
        private const val RETRY_DELAY_NANOS = RETRY_DELAY_MS * 1_000_000L

        // Token generation uses SecureRandom for ≥128-bit entropy (see #50 spec §1-3)
        internal fun newToken(size: Int = DEFAULT_TOKEN_LENGTH): String = Base58.randomString(size)

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

    private val syncCommands: RedisCommands<String, String> by lazy { connection.sync() }
    private val asyncCommands: RedisAsyncCommands<String, String> by lazy { connection.async() }

    fun isLocked(): Boolean = syncCommands.get(lockKey) != null

    fun isHeldByCurrentInstance(): Boolean {
        val token = tokenRef.value ?: return false
        return syncCommands.get(lockKey) == token
    }

    /**
     * `currentToken` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun currentToken(): String? = tokenRef.value

    // =========================================================================
    // 동기 API
    // =========================================================================

    fun tryLock(
        waitTime: Duration = Duration.ZERO,
        leaseTime: Duration = defaultLeaseTime,
    ): Boolean {
        val token = newToken()
        val leaseMs = leaseTime.inWholeMilliseconds
        val deadline = MonotonicDeadline.fromNow(waitTime)

        do {
            val args = SetArgs().nx().px(leaseMs)
            val result = syncCommands.set(lockKey, token, args)
            if (result != null) {
                tokenRef.value = token
                log.debug { "Lock 획득 성공: lockKey=$lockKey" }
                return true
            }
            val delayNanos = deadline.remainingNanosForPark(RETRY_DELAY_NANOS)
            if (delayNanos > 0L) {
                LockSupport.parkNanos(delayNanos)
            }
        } while (deadline.hasTimeRemaining())

        log.debug { "Lock 획득 실패 (timeout): lockKey=$lockKey" }
        return false
    }

    fun lock(leaseTime: Duration = defaultLeaseTime, maxWaitTime: Duration = 5.minutes) {
        // Token generation uses SecureRandom for ≥128-bit entropy (see #50 spec §1-3)
        val token = newToken()
        val leaseMs = leaseTime.inWholeMilliseconds
        val args = SetArgs().nx().px(leaseMs)
        val deadline = MonotonicDeadline.fromNow(maxWaitTime)

        while (true) {
            val result = syncCommands.set(lockKey, token, args)
            if (result != null) {
                tokenRef.value = token
                log.debug { "Lock 획득 성공: lockKey=$lockKey" }
                return
            }
            check(deadline.hasTimeRemaining()) { "Lock 획득 시간 초과: lockKey=$lockKey, maxWaitTime=$maxWaitTime" }
            LockSupport.parkNanos(deadline.remainingNanosForPark(RETRY_DELAY_NANOS))
        }
    }

    fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) {
        val token = tokenRef.getAndSet(null) ?: error("현재 인스턴스가 락을 보유하지 않습니다: lockKey=$lockKey")
        val remainingMs = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime).inWholeMilliseconds

        val released = RedisScriptRunner.run<Long>(
            syncCommands,
            UNLOCK_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(lockKey), token, remainingMs.toString()
        )
        check(released > 0L) { "Lock 해제 실패 (토큰 불일치 또는 만료): lockKey=$lockKey" }
        log.debug { "Lock 해제 성공: lockKey=$lockKey" }
    }

    /**
     * `extend` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun extend(leaseTime: Duration = defaultLeaseTime): Boolean =
        extendDetailed(leaseTime).isExtended

    /**
     * `extendDetailed` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun extendDetailed(leaseTime: Duration = defaultLeaseTime): ExtendOutcome {
        val token = tokenRef.value ?: return ExtendOutcome.NotHeld
        val leaseMs = leaseTime.inWholeMilliseconds

        val extended = RedisScriptRunner.run<Long>(
            syncCommands,
            EXTEND_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(lockKey),
            token, leaseMs.toString()
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
        val token = newToken()
        val leaseMs = leaseTime.inWholeMilliseconds
        val deadline = MonotonicDeadline.fromNow(waitTime)

        fun attempt(): CompletableFuture<Boolean> {
            val args = SetArgs().nx().px(leaseMs)
            return asyncCommands.set(lockKey, token, args).toCompletableFuture()
                .thenCompose { result ->
                    if (result != null) {
                        tokenRef.value = token
                        log.debug { "Lock 획득 성공 (async): lockKey=$lockKey" }
                        completableFutureOf(true)
                    } else if (deadline.hasTimeRemaining()) {
                        val delayMillis = deadline.remainingMillisForDelay(RETRY_DELAY_MS)
                        val delayed = CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS)
                        CompletableFuture.runAsync({}, delayed).thenCompose { attempt() }
                    } else {
                        log.debug { "Lock 획득 실패 (timeout, async): lockKey=$lockKey" }
                        completableFutureOf(false)
                    }
                }
        }

        return attempt()
    }

    fun lockAsync(
        leaseTime: Duration = defaultLeaseTime,
        maxWaitTime: Duration = 5.minutes,
    ): CompletableFuture<Unit> {
        val token = newToken()
        val leaseMs = leaseTime.inWholeMilliseconds
        val deadline = MonotonicDeadline.fromNow(maxWaitTime)

        fun attempt(): CompletableFuture<Unit> {
            val args = SetArgs().nx().px(leaseMs)
            return asyncCommands.set(lockKey, token, args)
                .toCompletableFuture()
                .thenCompose { result ->
                    if (result != null) {
                        tokenRef.value = token
                        log.debug { "Lock 획득 성공 (async): lockKey=$lockKey" }
                        completableFutureOf(Unit)
                    } else if (deadline.hasTimeRemaining()) {
                        val delayMillis = deadline.remainingMillisForDelay(RETRY_DELAY_MS)
                        val delayed = CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS)
                        CompletableFuture.runAsync({}, delayed).thenCompose { attempt() }
                    } else {
                        failedCompletableFutureOf(IllegalStateException("Lock 획득 시간 초과 (async): lockKey=$lockKey"))
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
            ?: return failedCompletableFutureOf(IllegalStateException("현재 인스턴스가 락을 보유하지 않습니다: lockKey=$lockKey"))
        val remainingMs = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime).inWholeMilliseconds

        return RedisScriptRunner.runAsync<Long>(
            asyncCommands,
            UNLOCK_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(lockKey),
            token, remainingMs.toString()
        ).thenApply { released ->
            check(released > 0L) { "Lock 해제 실패 (토큰 불일치 또는 만료, async): lockKey=$lockKey" }
            log.debug { "Lock 해제 성공 (async): lockKey=$lockKey" }
        }
    }

    fun extendAsync(leaseTime: Duration = defaultLeaseTime): CompletableFuture<Boolean> {
        val token = tokenRef.value ?: return completableFutureOf(false)
        val leaseMs = leaseTime.inWholeMilliseconds

        return RedisScriptRunner.runAsync<Long>(
            asyncCommands,
            EXTEND_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(lockKey),
            token, leaseMs.toString()
        ).thenApply { it > 0L }
    }

    override fun toString(): String {
        return ToStringBuilder(this)
            .add("lockKey", lockKey)
            .add("defaultLeaseTime", defaultLeaseTime)
            .toString()
    }
}
