package io.bluetape4k.leader.lettuce.semaphore

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.lettuce.script.RedisScript
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * Lettuce 기반 Redis ZSET + Lua 스크립트로 동작하는 slot-token group primitive 입니다.
 *
 * ## 동작/계약
 *
 * - 단일 키 `lg:{lockName}` 의 ZSET 에 `token = Base58.randomString(8)` 멤버를
 *   `score = expiryAtMs` 로 추가하여 슬롯을 표현합니다.
 * - ACQUIRE / RELEASE / STATUS 3개의 Lua 스크립트가 모두 `redis.call('TIME')` 을 사용해
 *   **Redis 서버 시간** 만으로 만료를 판정하므로 클라이언트 clock skew 영향이 없습니다.
 * - ACQUIRE 시점에 `ZREMRANGEBYSCORE 0 nowMs` 로 만료된 entry 를 자동 회수합니다.
 *   클라이언트 crash 시 release 가 호출되지 않더라도 다음 acquire 시 자동 정리됩니다.
 * - RELEASE 는 `remainingMinLeaseMs > 0` 이면 ZADD XX 로 score 를 갱신하여 슬롯을 유지합니다.
 *   minLeaseTime 시맨틱을 backend TTL 에 위임하는 방식입니다 (caller-park 없음).
 * - 슬롯 획득 spin-poll 은 50ms 간격이며, deadline 초과 시 `null` 을 반환합니다.
 *   기존 `LettuceSemaphore` 와 달리 `IllegalStateException` 을 던지지 않습니다.
 * - token 은 caller stack 이 보유하므로 동일 인스턴스가 여러 슬롯을 동시에 잡아도 안전합니다.
 *
 * ## 사용 예
 *
 * ```kotlin
 * val group = LettuceSlotTokenGroup(connection, "batch-job", maxLeaders = 3)
 * val token = group.tryAcquire(5.seconds) ?: return
 * try {
 *     doWork()
 * } finally {
 *     group.release(token, remainingMinLeaseMs = 0)
 * }
 * ```
 *
 * @param connection Lettuce [StatefulRedisConnection] (StringCodec 기반)
 * @param lockName   slot 식별 이름 (`lg:{lockName}` 으로 prefix 됨)
 * @param maxLeaders 최대 동시 점유 슬롯 수 (>= 1)
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
         * ACQUIRE 스크립트.
         *
         * KEYS[1] = slotKey
         * ARGV[1] = maxLeaders
         * ARGV[2] = token (Base58 8자)
         * ARGV[3] = leaseTimeMs
         *
         * 반환: token (성공) 또는 빈 문자열 (실패)
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
  return ARGV[2]
end
return ''
"""
        )

        /**
         * RELEASE 스크립트.
         *
         * KEYS[1] = slotKey
         * ARGV[1] = token
         * ARGV[2] = remainingMinLeaseMs
         *
         * remainingMinLeaseMs > 0 → 현재 score 가 nowMs 보다 큰 경우(아직 살아있는 token)에만
         *                            ZADD XX 로 score 갱신 (slot 유지). expired token 부활 방지.
         * 그 외 → ZREM 으로 즉시 해제
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
  return redis.call('ZREM', KEYS[1], ARGV[1])
end
"""
        )

        /**
         * STATUS 스크립트.
         *
         * KEYS[1] = slotKey
         * ARGV[1] = maxLeaders
         *
         * 반환: { active, available }
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
         * EXTEND 스크립트 (T7 PR 2, AC-16).
         *
         * 서버 시간 `redis.call('TIME')` 만 사용하여 client clock skew 영향 없이 만료 판정.
         *
         * KEYS[1] = slotKey
         * ARGV[1] = token (Base58 8자)
         * ARGV[2] = leaseTimeMs
         *
         * 반환:
         *  - `1` : 토큰이 살아있고 (score > nowMs) score 를 `nowMs + leaseTimeMs` 로 갱신
         *  - `0` : 토큰 부재 또는 이미 만료 — extend 실패
         *
         * ZSET key TTL `PEXPIRE k leaseTimeMs + SLOT_KEY_TTL_MARGIN_MS` 도 함께 갱신.
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
  return 1
end
return 0
"""
        )

        /**
         * IS_HELD 스크립트 (T7 PR 2).
         *
         * server-side TIME 으로 만료 여부 판정 — client clock skew 차단.
         *
         * KEYS[1] = slotKey
         * ARGV[1] = token
         *
         * 반환: `1` (살아있음) / `0` (만료 또는 부재)
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
        lockName.requireNotBlank("lockName")
        maxLeaders.requirePositiveNumber("maxLeaders")
    }

    val slotKey: String = "$KEY_PREFIX$lockName$KEY_SUFFIX"

    private val syncCommands: RedisCommands<String, String> = connection.sync()
    private val asyncCommands: RedisAsyncCommands<String, String> = connection.async()

    // =========================================================================
    // 동기 API
    // =========================================================================

    /**
     * 슬롯을 동기 방식으로 획득합니다.
     *
     * @param waitTime 슬롯 대기 최대 시간
     * @return 획득 성공 시 token, 실패 시 `null`
     */
    fun tryAcquire(waitTime: Duration, leaseTime: Duration): String? {
        val token = Base58.randomString(TOKEN_LENGTH)
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds
        val leaseMs = leaseTime.inWholeMilliseconds.toString()
        while (true) {
            val result = RedisScriptRunner.run<String>(
                syncCommands, ACQUIRE_SCRIPT, ScriptOutputType.VALUE,
                arrayOf(slotKey), maxLeaders.toString(), token, leaseMs
            )
            if (!result.isNullOrEmpty()) {
                log.debug { "슬롯 획득 성공. slotKey=$slotKey, token=$token" }
                return result
            }
            if (System.currentTimeMillis() >= deadline) {
                log.debug { "슬롯 획득 타임아웃. slotKey=$slotKey, waitTime=$waitTime" }
                return null
            }
            LockSupport.parkNanos(SPIN_DELAY_NANOS)
        }
    }

    /**
     * 슬롯의 lease 를 atomic 으로 연장합니다 (sync, T7 PR 2, AC-16).
     *
     * ## 동작/계약
     * - server-side `redis.call('TIME')` 으로 client clock skew 영향 차단
     * - token 의 score 가 nowMs 보다 작으면 (이미 만료) → [ExtendOutcome.NotHeld]
     * - score > nowMs → ZADD XX 로 `nowMs + leaseTime` 갱신 + ZSET key TTL `PEXPIRE` 갱신 → [ExtendOutcome.Extended]
     *
     * **caller 가 backend exception 을 try/catch 로 [ExtendOutcome.BackendError] 변환**.
     */
    fun extendSlot(token: String, leaseTime: Duration): ExtendOutcome {
        token.requireNotBlank("token")
        val leaseMs = leaseTime.inWholeMilliseconds
        val extended = RedisScriptRunner.run<Long>(
            syncCommands, EXTEND_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(slotKey), token, leaseMs.toString()
        )
        return if (extended > 0L) {
            ExtendOutcome.Extended(Instant.now().plusMillis(leaseMs))
        } else {
            ExtendOutcome.NotHeld
        }
    }

    /**
     * 슬롯이 backend 에 살아있는지 확인합니다 (sync).
     *
     * server-side `redis.call('TIME')` 으로 만료 여부 판정 — client clock skew 차단.
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
     * 슬롯을 즉시 해제합니다 (`remainingMinLeaseMs <= 0`) 또는 score 만 갱신하여 유지합니다.
     */
    fun release(token: String, remainingMinLeaseMs: Long) {
        token.requireNotBlank("token")
        val ret = RedisScriptRunner.run<Long>(
            syncCommands, RELEASE_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(slotKey), token, remainingMinLeaseMs.toString()
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
     * 슬롯을 비동기 방식으로 획득합니다.
     *
     * @param waitTime  슬롯 대기 최대 시간
     * @param leaseTime 슬롯 점유 시간 (만료 시 자동 회수)
     * @return 획득 성공 시 token, contention 으로 deadline 도달 시 `null` 을 담은 [CompletableFuture].
     *         backend error (Redis connection/auth/script 오류) 가 retry deadline 까지 해소되지 않으면
     *         마지막 error 로 future 가 실패합니다.
     *
     * ### Caveats
     *
     * - 재시도 사이 지연은 [CompletableFuture.delayedExecutor] 의 글로벌 단일 스케줄러 thread 를
     *   사용합니다. 다수의 동시 acquire 가 spin-poll 중이라면 이 스케줄러가 병목이 될 수 있습니다.
     * - 일시적 script error (예: 일시적 connection failure) 는 retry 안에서 swallow 되며 다음 시도까지
     *   대기합니다. 그러나 deadline 도달 시점까지 error 가 해소되지 않으면 (그리고 contention 도 아니면)
     *   마지막 error 가 caller 에게 전달됩니다. sync / suspend path 와 동일하게 backend outage 를
     *   silent 하게 무시하지 않습니다.
     * - token 은 attempt() 외부에서 1회만 생성합니다. ACQUIRE 스크립트가 ZADD 로 token 을 멤버로
     *   사용하므로 retry 시 동일 token 을 재사용해야 ghost entry 가 발생하지 않습니다.
     */
    fun tryAcquireAsync(waitTime: Duration, leaseTime: Duration): CompletableFuture<String?> {
        val token = Base58.randomString(TOKEN_LENGTH)
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds
        val leaseMs = leaseTime.inWholeMilliseconds.toString()
        val lastError = AtomicReference<Throwable?>(null)

        fun attempt(): CompletableFuture<String?> {
            return RedisScriptRunner.runAsync<String>(
                asyncCommands, ACQUIRE_SCRIPT, ScriptOutputType.VALUE,
                arrayOf(slotKey), maxLeaders.toString(), token, leaseMs
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
                    !result.isNullOrEmpty() -> CompletableFuture.completedFuture<String?>(result)
                    System.currentTimeMillis() < deadline -> {
                        val delayed = CompletableFuture.delayedExecutor(SPIN_DELAY_MS, TimeUnit.MILLISECONDS)
                        CompletableFuture.runAsync({}, delayed).thenCompose { attempt() }
                    }
                    else -> {
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
            arrayOf(slotKey), token, remainingMinLeaseMs.toString()
        ).thenApply {
            log.debug {
                "슬롯 해제 (async). slotKey=$slotKey, token=$token, remainingMinLeaseMs=$remainingMinLeaseMs"
            }
            Unit
        }
    }

    // =========================================================================
    // 코루틴 API (suspend)
    // =========================================================================

    suspend fun tryAcquireSuspending(waitTime: Duration, leaseTime: Duration): String? {
        val token = Base58.randomString(TOKEN_LENGTH)
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds
        val leaseMs = leaseTime.inWholeMilliseconds.toString()
        while (true) {
            val result = RedisScriptRunner.runSuspending<String>(
                asyncCommands, ACQUIRE_SCRIPT, ScriptOutputType.VALUE,
                arrayOf(slotKey), maxLeaders.toString(), token, leaseMs
            )
            if (!result.isNullOrEmpty()) {
                log.debug { "슬롯 획득 성공 (suspend). slotKey=$slotKey, token=$token" }
                return result
            }
            if (System.currentTimeMillis() >= deadline) {
                log.debug { "슬롯 획득 타임아웃 (suspend). slotKey=$slotKey, waitTime=$waitTime" }
                return null
            }
            delay(SPIN_DELAY_MS)
        }
    }

    /**
     * 슬롯의 lease 를 atomic 으로 연장합니다 (suspend, T7 PR 2, AC-16).
     *
     * - Lettuce `asyncCommands` 는 Netty event-loop 기반 non-blocking 이지만 R9 권고에 따라
     *   suspend 진입점은 `coroutineContext.ensureActive()` 로 cancellation 을 명시적으로 확인.
     */
    suspend fun extendSlotSuspending(token: String, leaseTime: Duration): ExtendOutcome {
        coroutineContext.ensureActive()
        token.requireNotBlank("token")
        val leaseMs = leaseTime.inWholeMilliseconds
        val extended = RedisScriptRunner.runSuspending<Long>(
            asyncCommands, EXTEND_SCRIPT, ScriptOutputType.INTEGER,
            arrayOf(slotKey), token, leaseMs.toString()
        )
        return if (extended > 0L) {
            ExtendOutcome.Extended(Instant.now().plusMillis(leaseMs))
        } else {
            ExtendOutcome.NotHeld
        }
    }

    /**
     * 슬롯이 backend 에 살아있는지 확인합니다 (suspend).
     */
    suspend fun isSlotHeldSuspending(token: String): Boolean {
        coroutineContext.ensureActive()
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
            arrayOf(slotKey), token, remainingMinLeaseMs.toString()
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
