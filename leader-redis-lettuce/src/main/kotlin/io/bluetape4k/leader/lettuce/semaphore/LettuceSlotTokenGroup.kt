package io.bluetape4k.leader.lettuce.semaphore

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.lettuce.internal.MonotonicDeadline
import io.bluetape4k.leader.lettuce.script.RedisScript
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration

/**
 * `LettuceSlotTokenGroup`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property connection Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockName Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property maxLeaders Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class LettuceSlotTokenGroup(
    private val connection: StatefulRedisConnection<String, String>,
    val lockName: String,
    val maxLeaders: Int,
) {
    companion object: KLogging() {
        private const val SPIN_DELAY_MS = 50L
        private const val SPIN_DELAY_NANOS = SPIN_DELAY_MS * 1_000_000L
        private const val SLOT_KEY_TTL_MARGIN_MS = 5_000L

        // Token generation uses SecureRandom for ≥128-bit entropy (see #50 spec §1-3)
        private const val TOKEN_LENGTH = 22
        private const val KEY_PREFIX = "lg:{"
        private const val KEY_SUFFIX = "}"

        /**
         * `ACQUIRE_SCRIPT` 값은 Redis Lettuce backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        private val ACQUIRE_SCRIPT = RedisScript(
            """
redis.replicate_commands()
local t = redis.call('TIME')
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, nowMs)
if redis.call('ZCARD', KEYS[1]) < tonumber(ARGV[1]) then
  redis.call('ZADD', KEYS[1], nowMs + tonumber(ARGV[3]), ARGV[2])
  redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]) + $SLOT_KEY_TTL_MARGIN_MS)
  if ARGV[4] ~= '' then
    redis.call('HSET', KEYS[2], ARGV[2], ARGV[4])
    redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[3]) + $SLOT_KEY_TTL_MARGIN_MS)
  end
  return ARGV[2]
end
return ''
"""
        )

        /**
         * `RELEASE_SCRIPT` 값은 Redis Lettuce backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        private val RELEASE_SCRIPT = RedisScript(
            """
redis.replicate_commands()
if tonumber(ARGV[2]) > 0 then
  local t = redis.call('TIME')
  local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
  local cur = redis.call('ZSCORE', KEYS[1], ARGV[1])
  if cur and tonumber(cur) > nowMs then
    return redis.call('ZADD', KEYS[1], 'XX', nowMs + tonumber(ARGV[2]), ARGV[1])
  end
  return 0
else
  redis.call('HDEL', KEYS[2], ARGV[1])
  return redis.call('ZREM', KEYS[1], ARGV[1])
end
"""
        )

        /**
         * `STATUS_SCRIPT` 값은 Redis Lettuce backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        private val STATUS_SCRIPT = RedisScript(
            """
redis.replicate_commands()
local t = redis.call('TIME')
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, nowMs)
local active = redis.call('ZCARD', KEYS[1])
return { active, tonumber(ARGV[1]) - active }
"""
        )

        /**
         * `EXTEND_SCRIPT` 값은 Redis Lettuce backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        private val EXTEND_SCRIPT = RedisScript(
            """
redis.replicate_commands()
local t = redis.call('TIME')
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local cur = redis.call('ZSCORE', KEYS[1], ARGV[1])
if cur and tonumber(cur) > nowMs then
  redis.call('ZADD', KEYS[1], 'XX', nowMs + tonumber(ARGV[2]), ARGV[1])
  redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]) + $SLOT_KEY_TTL_MARGIN_MS)
  redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[2]) + $SLOT_KEY_TTL_MARGIN_MS)
  return 1
end
return 0
"""
        )

        /**
         * `IS_HELD_SCRIPT` 값은 Redis Lettuce backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        private val IS_HELD_SCRIPT = RedisScript(
            """
redis.replicate_commands()
local t = redis.call('TIME')
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local cur = redis.call('ZSCORE', KEYS[1], ARGV[1])
if cur and tonumber(cur) > nowMs then
  return 1
end
return 0
"""
        )
    }

    init {
        validateLockName(lockName)
        maxLeaders.requirePositiveNumber("maxLeaders")
    }

    val slotKey: String = "$KEY_PREFIX$lockName$KEY_SUFFIX"
    val metaKey: String = "$slotKey:meta"

    private val syncCommands: RedisCommands<String, String> = connection.sync()
    private val asyncCommands: RedisAsyncCommands<String, String> = connection.async()

    // =========================================================================
    // 동기 API
    // =========================================================================

    /**
     * `tryAcquire` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun tryAcquire(waitTime: Duration, leaseTime: Duration, auditLeaderId: String = ""): String? {
        val token = Base58.randomString(TOKEN_LENGTH)
        val deadline = MonotonicDeadline.fromNow(waitTime)
        val leaseMs = leaseTime.inWholeMilliseconds.toString()
        while (true) {
            val result = RedisScriptRunner.run<String>(
                syncCommands, ACQUIRE_SCRIPT, ScriptOutputType.VALUE,
                arrayOf(slotKey, metaKey), maxLeaders.toString(), token, leaseMs, auditLeaderId
            )
            if (!result.isEmpty()) {
                log.debug { "슬롯 획득 성공. slotKey=$slotKey, token=$token" }
                return result
            }
            val delayNanos = deadline.remainingNanosForPark(SPIN_DELAY_NANOS)
            if (delayNanos <= 0L) {
                log.debug { "슬롯 획득 타임아웃. slotKey=$slotKey, waitTime=$waitTime" }
                return null
            }
            LockSupport.parkNanos(delayNanos)
        }
    }

    /**
     * `extendSlot` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun extendSlot(token: String, leaseTime: Duration): ExtendOutcome {
        token.requireNotBlank("token")
        val leaseMs = leaseTime.inWholeMilliseconds
        val extended = RedisScriptRunner.run<Long>(
            syncCommands, EXTEND_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(slotKey, metaKey), token, leaseMs.toString()
        )
        return if (extended > 0L) {
            ExtendOutcome.Extended(Instant.now().plusMillis(leaseMs))
        } else {
            ExtendOutcome.NotHeld
        }
    }

    /**
     * `isSlotHeld` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun isSlotHeld(token: String): Boolean {
        token.requireNotBlank("token")
        val result = RedisScriptRunner.run<Long>(
            syncCommands, IS_HELD_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(slotKey), token
        )
        return result > 0L
    }

    /**
     * `release` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun release(token: String, remainingMinLeaseMs: Long) {
        token.requireNotBlank("token")
        val ret = RedisScriptRunner.run<Long>(
            syncCommands, RELEASE_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(slotKey, metaKey), token, remainingMinLeaseMs.toString()
        )
        log.debug {
            "슬롯 해제. slotKey=$slotKey, token=$token, remainingMinLeaseMs=$remainingMinLeaseMs, ret=$ret"
        }
    }

    fun activeCount(): Int = status().first
    fun availableSlots(): Int = status().second

    private fun status(): Pair<Int, Int> {
        @Suppress("UNCHECKED_CAST")
        val list = RedisScriptRunner.run<List<Long>>(
            syncCommands, STATUS_SCRIPT, ScriptOutputType.MULTI,
            arrayOf(slotKey), maxLeaders.toString()
        )
        val active = list.getOrNull(0)?.toInt() ?: 0
        val available = list.getOrNull(1)?.toInt() ?: maxLeaders
        return active to available
    }

    // =========================================================================
    // 비동기 API (CompletableFuture)
    // =========================================================================

    /**
     * `tryAcquireAsync` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun tryAcquireAsync(
        waitTime: Duration,
        leaseTime: Duration,
        auditLeaderId: String = "",
    ): CompletableFuture<String?> {
        val token = Base58.randomString(TOKEN_LENGTH)
        val deadline = MonotonicDeadline.fromNow(waitTime)
        val leaseMs = leaseTime.inWholeMilliseconds.toString()
        val lastError = AtomicReference<Throwable?>(null)

        fun attempt(): CompletableFuture<String?> {
            return RedisScriptRunner.runAsync<String>(
                asyncCommands, ACQUIRE_SCRIPT, ScriptOutputType.VALUE,
                arrayOf(slotKey, metaKey), maxLeaders.toString(), token, leaseMs, auditLeaderId
            ).handle { result, error ->
                if (error != null) {
                    log.warn(error) { "ACQUIRE 스크립트 오류 (async retry). slotKey=$slotKey" }
                    lastError.set(error)
                    null
                } else {
                    lastError.set(null)
                    result
                }
            }.thenCompose { result ->
                when {
                    !result.isNullOrEmpty()     -> CompletableFuture.completedFuture<String?>(result)
                    deadline.hasTimeRemaining() -> {
                        val delayMillis = deadline.remainingMillisForDelay(SPIN_DELAY_MS)
                        val delayed = CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS)
                        CompletableFuture.runAsync({}, delayed).thenCompose { attempt() }
                    }
                    else                        -> {
                        // deadline 도달 시점에 마지막 error 가 있으면 backend outage 로 간주하여 surface.
                        // contention (script 정상 실행 + 빈 문자열 반환) 만 null 로 반환.
                        val terminalError = lastError.get()
                        if (terminalError != null) {
                            CompletableFuture.failedFuture(terminalError)
                        } else {
                            CompletableFuture.completedFuture<String?>(null)
                        }
                    }
                }
            }
        }
        return attempt()
    }

    fun releaseAsync(token: String, remainingMinLeaseMs: Long): CompletableFuture<Unit> {
        token.requireNotBlank("token")
        return RedisScriptRunner.runAsync<Long>(
            asyncCommands, RELEASE_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(slotKey, metaKey), token, remainingMinLeaseMs.toString()
        ).thenApply {
            log.debug {
                "슬롯 해제 (async). slotKey=$slotKey, token=$token, remainingMinLeaseMs=$remainingMinLeaseMs"
            }
        }
    }

    // =========================================================================
    // 코루틴 API (suspend)
    // =========================================================================

    suspend fun tryAcquireSuspending(waitTime: Duration, leaseTime: Duration, auditLeaderId: String = ""): String? {
        val token = Base58.randomString(TOKEN_LENGTH)
        val deadline = MonotonicDeadline.fromNow(waitTime)
        val leaseMs = leaseTime.inWholeMilliseconds.toString()
        while (true) {
            val result = RedisScriptRunner.runSuspending<String>(
                asyncCommands, ACQUIRE_SCRIPT, ScriptOutputType.VALUE,
                arrayOf(slotKey, metaKey), maxLeaders.toString(), token, leaseMs, auditLeaderId
            )
            if (!result.isEmpty()) {
                log.debug { "슬롯 획득 성공 (suspend). slotKey=$slotKey, token=$token" }
                return result
            }
            val delayMillis = deadline.remainingMillisForDelay(SPIN_DELAY_MS)
            if (delayMillis <= 0L) {
                log.debug { "슬롯 획득 타임아웃 (suspend). slotKey=$slotKey, waitTime=$waitTime" }
                return null
            }
            delay(timeMillis = delayMillis)
        }
    }

    /**
     * `extendSlotSuspending` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun extendSlotSuspending(token: String, leaseTime: Duration): ExtendOutcome {
        currentCoroutineContext().ensureActive()
        token.requireNotBlank("token")
        val leaseMs = leaseTime.inWholeMilliseconds
        val extended = RedisScriptRunner.runSuspending<Long>(
            asyncCommands, EXTEND_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(slotKey, metaKey), token, leaseMs.toString()
        )
        return if (extended > 0L) {
            ExtendOutcome.Extended(Instant.now().plusMillis(leaseMs))
        } else {
            ExtendOutcome.NotHeld
        }
    }

    /**
     * `isSlotHeldSuspending` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun isSlotHeldSuspending(token: String): Boolean {
        currentCoroutineContext().ensureActive()
        token.requireNotBlank("token")
        val result = RedisScriptRunner.runSuspending<Long>(
            asyncCommands, IS_HELD_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(slotKey), token
        )
        return result > 0L
    }

    suspend fun releaseSuspending(token: String, remainingMinLeaseMs: Long) {
        token.requireNotBlank("token")
        val ret = RedisScriptRunner.runSuspending<Long>(
            asyncCommands, RELEASE_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(slotKey, metaKey), token, remainingMinLeaseMs.toString()
        )
        log.debug {
            "슬롯 해제 (suspend). slotKey=$slotKey, token=$token, remainingMinLeaseMs=$remainingMinLeaseMs, ret=$ret"
        }
    }

    suspend fun activeCountSuspending(): Int = statusSuspending().first
    suspend fun availableSlotsSuspending(): Int = statusSuspending().second

    private suspend fun statusSuspending(): Pair<Int, Int> {
        @Suppress("UNCHECKED_CAST")
        val list = RedisScriptRunner.runSuspending<List<Long>>(
            asyncCommands, STATUS_SCRIPT, ScriptOutputType.MULTI,
            arrayOf(slotKey), maxLeaders.toString()
        )
        val active = list.getOrNull(0)?.toInt() ?: 0
        val available = list.getOrNull(1)?.toInt() ?: maxLeaders
        return active to available
    }
}
