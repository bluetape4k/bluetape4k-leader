package io.bluetape4k.leader.lettuce.lock

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.lettuce.DEFAULT_TOKEN_LENGTH
import io.bluetape4k.leader.lettuce.DefaultLeaseTime
import io.bluetape4k.leader.lettuce.internal.MonotonicDeadline
import io.bluetape4k.leader.lettuce.script.RedisScript
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * `LettuceSuspendLock`는 Redis Lettuce lock key의 token 기반 tryLock·ownership 확인·unlock·lease 연장을 suspend 경로로 수행합니다.
 *
 * `tryLock`이 경합·timeout으로 ownership을 획득하지 못하면 `false`를 반환하며, unlock/extend는 backend token 일치 여부를 확인해 불일치·만료를 성공으로 처리하지 않습니다.
 * @property connection suspend lock key의 token을 저장하고 Lua script를 실행하는 Redis 연결입니다.
 * @property lockKey token을 저장하는 Redis string key입니다. 그룹 선출에서는 슬롯별 key가 전달됩니다.
 * @property defaultLeaseTime `tryLock`·`extend` 등 lease 인자를 생략했을 때 적용하는 기본 TTL이며, 단위는 `Duration`입니다.
 */
class LettuceSuspendLock(
    private val connection: StatefulRedisConnection<String, String>,
    val lockKey: String,
    val defaultLeaseTime: Duration = DefaultLeaseTime,
) {
    companion object: KLoggingChannel() {
        private const val RETRY_DELAY_MS = 50L
        private const val DEFAULT_MAX_WAIT_MINUTES = 5L

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

    private val asyncCommands: RedisAsyncCommands<String, String> by lazy { connection.async() }

    suspend fun isLocked(): Boolean = asyncCommands.get(lockKey).await() != null

    suspend fun isHeldByCurrentInstance(): Boolean {
        val token = tokenRef.value ?: return false
        return asyncCommands.get(lockKey).await() == token
    }

    /** 이 인스턴스가 마지막으로 성공한 획득에서 발급받은 소유 token을 반환하며, 미획득 상태는 `null`입니다. token은 ownership 비밀값입니다. */
    fun currentToken(): String? = tokenRef.value

    suspend fun tryLock(
        waitTime: Duration = Duration.ZERO,
        leaseTime: Duration = defaultLeaseTime,
    ): Boolean {
        val token = newToken()
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
        val token = newToken()
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
            tokenRef.getAndSet(null) ?: error("현재 인스턴스가 락을 보유하지 않습니다: lockKey=$lockKey")
        val remainingMs = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime).inWholeMilliseconds

        val released = RedisScriptRunner.runSuspending<Long>(
            asyncCommands,
            UNLOCK_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(lockKey),
            token, remainingMs.toString()
        )

        check(released > 0L) { "Lock 해제 실패 (토큰 불일치 또는 만료, suspend): lockKey=$lockKey" }
        log.debug { "Lock 해제 성공 (suspend): lockKey=$lockKey" }
    }

    /** Lua 소유 검증 결과를 `Boolean`으로 축약해 반환합니다. token 불일치·만료면 `false`입니다. */
    suspend fun extend(leaseTime: Duration = defaultLeaseTime): Boolean =
        extendDetailed(leaseTime).isExtended

    /** Lua에서 현재 token을 검증해 TTL을 갱신하고 `Extended` 또는 `NotHeld`를 반환합니다. */
    suspend fun extendDetailed(leaseTime: Duration = defaultLeaseTime): ExtendOutcome {
        currentCoroutineContext().ensureActive()
        val token = tokenRef.value ?: return ExtendOutcome.NotHeld
        val leaseMs = leaseTime.inWholeMilliseconds

        val extended = RedisScriptRunner.runSuspending<Long>(
            asyncCommands,
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

    override fun toString(): String {
        return ToStringBuilder(this)
            .add("lockKey", lockKey)
            .add("defaultLeaseTime", defaultLeaseTime)
            .toString()
    }
}
