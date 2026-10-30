package io.bluetape4k.leader.lettuce.semaphore

import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.lettuce.DEFAULT_TOKEN_LENGTH
import io.bluetape4k.leader.lettuce.internal.MonotonicDeadline
import io.bluetape4k.leader.lettuce.script.RedisScript
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.asInt
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * `LettuceSlotTokenGroup`는 lockName별 Redis sorted-set·metadata hash와 Lua script로 그룹 slot token의 획득·연장·보유 확인·해제를 수행합니다.
 *
 * @property connection slot acquire/release/extend Lua script를 실행하는 Redis 연결입니다.
 * @property lockName 논리적 그룹 이름이며 slot key는 `lg:{$lockName}`, metadata hash는 `lg:{$lockName}:meta`로 파생됩니다.
 * @property maxLeaders Redis sorted-set에서 동시에 유효하게 유지할 token의 최대 개수이며 양수여야 합니다.
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
        internal fun newToken(size: Int = DEFAULT_TOKEN_LENGTH): String = Base58.randomString(size)

        private const val KEY_PREFIX = "lg:{"
        private const val KEY_SUFFIX = "}"

        /** Redis server time으로 만료 token을 정리한 뒤 `maxLeaders` 미만이면 token과 선택적 audit ID를 저장합니다. */
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
            """.trimIndent()
        )

        /** 남은 최소 lease가 있으면 현재 token의 score를 갱신하고, 없으면 token과 metadata를 제거합니다. */
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
            """.trimIndent()
        )

        /** 만료 token을 정리한 뒤 `{active, maxLeaders - active}`를 반환합니다. */
        private val STATUS_SCRIPT = RedisScript(
            """
            redis.replicate_commands()
            local t = redis.call('TIME')
            local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, nowMs)
            local active = redis.call('ZCARD', KEYS[1])
            return { active, tonumber(ARGV[1]) - active }
            """.trimIndent()
        )

        /** 유효한 token의 score와 key TTL을 `leaseTime`만큼 갱신합니다. */
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
            """.trimIndent()
        )

        /** token score가 Redis server time보다 큰지 검사해 소유 여부를 1 또는 0으로 반환합니다. */
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
            """.trimIndent()
        )
    }

    init {
        lockName.validateLockName()
        maxLeaders.requirePositiveNumber("maxLeaders")
    }

    val slotKey: String = "$KEY_PREFIX$lockName$KEY_SUFFIX"
    val metaKey: String = "$slotKey:meta"

    private val syncCommands: RedisCommands<String, String> = connection.sync()
    private val asyncCommands: RedisAsyncCommands<String, String> = connection.async()

    // =========================================================================
    // 동기 API
    // =========================================================================

    /** `waitTime` 동안 최대 50ms 간격으로 slot을 재시도해 소유 token을 반환하며, 빈 문자열 응답은 `null`입니다. */
    fun tryAcquire(waitTime: Duration, leaseTime: Duration, auditLeaderId: String = ""): String? {
        val token = newToken()
        val deadline = MonotonicDeadline.fromNow(waitTime)
        val leaseMs = leaseTime.inWholeMilliseconds.toString()
        while (true) {
            val result = RedisScriptRunner.run<String>(
                syncCommands, ACQUIRE_SCRIPT, ScriptOutputType.VALUE,
                arrayOf(slotKey, metaKey), maxLeaders.toString(), token, leaseMs, auditLeaderId
            )
            if (result.isNotEmpty()) {
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

    /** token을 검증해 slot lease를 갱신하고, 현재 소유가 아니면 `NotHeld`를 반환합니다. */
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

    /** Redis server time 기준으로 token이 아직 만료되지 않았는지 검사합니다. */
    fun isSlotHeld(token: String): Boolean {
        token.requireNotBlank("token")
        val result = RedisScriptRunner.run<Long>(
            syncCommands, IS_HELD_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(slotKey), token
        )
        return result > 0L
    }

    /** token 소유를 확인해 즉시 제거하거나 `remainingMinLeaseMs`만큼 최소 lease를 보존합니다. token은 ownership 비밀값입니다. */
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

    /** async Lua 호출을 최대 `waitTime`까지 재시도하며 정상 경합은 `null`, 마지막 backend 오류는 failed future로 구분합니다. */
    fun tryAcquireAsync(
        waitTime: Duration,
        leaseTime: Duration,
        auditLeaderId: String = "",
    ): CompletableFuture<String?> {
        val token = newToken()
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
                    !result.isNullOrEmpty()     -> completableFutureOf(result)
                    deadline.hasTimeRemaining() -> {
                        val delayMillis = deadline.remainingMillisForDelay(SPIN_DELAY_MS)
                        val delayed = CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS)
                        futureOf(delayed) { }.thenCompose { attempt() }
                    }
                    else                        -> {
                        // deadline 도달 시점에 마지막 error 가 있으면 backend outage 로 간주하여 surface.
                        // contention (script 정상 실행 + 빈 문자열 반환) 만 null 로 반환.
                        val terminalError = lastError.get()
                        if (terminalError != null) {
                            failedCompletableFutureOf(terminalError)
                        } else {
                            completableFutureOf(null)
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
            log.debug { "슬롯 해제 (async). slotKey=$slotKey, token=$token, remainingMinLeaseMs=$remainingMinLeaseMs" }
        }
    }

    // =========================================================================
    // 코루틴 API (suspend)
    // =========================================================================

    suspend fun tryAcquireSuspending(waitTime: Duration, leaseTime: Duration, auditLeaderId: String = ""): String? {
        val token = newToken()
        val deadline = MonotonicDeadline.fromNow(waitTime)
        val leaseMs = leaseTime.inWholeMilliseconds.toString()
        while (true) {
            val result = RedisScriptRunner.runSuspending<String>(
                asyncCommands, ACQUIRE_SCRIPT, ScriptOutputType.VALUE,
                arrayOf(slotKey, metaKey), maxLeaders.toString(), token, leaseMs, auditLeaderId
            )
            if (result.isNotEmpty()) {
                log.debug { "슬롯 획득 성공 (suspend). slotKey=$slotKey, token=$token" }
                return result
            }
            val delayMillis = deadline.remainingMillisForDelay(SPIN_DELAY_MS)
            if (delayMillis <= 0L) {
                log.debug { "슬롯 획득 타임아웃 (suspend). slotKey=$slotKey, waitTime=$waitTime" }
                return null
            }
            delay(delayMillis.milliseconds)
        }
    }

    /** suspend Lua 호출로 token이 유효할 때만 slot lease를 연장하고 취소를 먼저 검사합니다. */
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

    /** suspend Lua 호출로 Redis server time 기준 token 유효성을 검사합니다. */
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
        val active = list.getOrNull(0).asInt(0)
        val available = list.getOrNull(1).asInt(maxLeaders)
        return active to available
    }
}
