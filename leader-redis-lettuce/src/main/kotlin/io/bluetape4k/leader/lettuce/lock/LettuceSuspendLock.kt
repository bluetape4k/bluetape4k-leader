package io.bluetape4k.leader.lettuce.lock

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.leader.lettuce.internal.MonotonicDeadline
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
 * `LettuceSuspendLock`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property connection Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockKey Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property defaultLeaseTime Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
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
     * `currentToken` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun currentToken(): String? = tokenRef.value

    suspend fun tryLock(
        waitTime: Duration = Duration.ZERO,
        leaseTime: Duration = defaultLeaseTime,
    ): Boolean {
        // Token generation uses SecureRandom for ≥128-bit entropy (see #50 spec §1-3)
        val token = Base58.randomString(length = 22)
        val leaseMs = leaseTime.inWholeMilliseconds
        val deadline = MonotonicDeadline.fromNow(waitTime)

        do {
            val args = SetArgs().nx().px(leaseMs)
            val result = asyncCommands.set(lockKey, token, args).await()
            if (result != null) {
                tokenRef.value = token
                log.debug { "Lock 획득 성공 (suspend): lockKey=$lockKey" }
                return true
            }
            val delayMillis = deadline.remainingMillisForDelay(RETRY_DELAY_MS)
            if (delayMillis > 0L) {
                delay(delayMillis.milliseconds)
            }
        } while (deadline.hasTimeRemaining())

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
        val deadline = MonotonicDeadline.fromNow(maxWaitTime)

        while (true) {
            val args = SetArgs().nx().px(leaseMs)
            val result = asyncCommands.set(lockKey, token, args).await()
            if (result != null) {
                tokenRef.value = token
                log.debug { "Lock 획득 성공 (suspend): lockKey=$lockKey" }
                return
            }
            check(deadline.hasTimeRemaining()) {
                "Lock 획득 시간 초과 (suspend): lockKey=$lockKey, maxWaitTime=$maxWaitTime"
            }
            delay(deadline.remainingMillisForDelay(RETRY_DELAY_MS).milliseconds)
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
     * `extend` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun extend(leaseTime: Duration = defaultLeaseTime): Boolean =
        extendDetailed(leaseTime).isExtended

    /**
     * `extendDetailed` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
