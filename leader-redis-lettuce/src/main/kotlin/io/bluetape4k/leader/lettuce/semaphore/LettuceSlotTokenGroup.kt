package io.bluetape4k.leader.lettuce.semaphore

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.lettuce.internal.MonotonicDeadline
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
 * Slot-token group primitive backed by Lettuce Redis ZSET + Lua scripts.
 *
 * ## Behavior / Contract
 *
 * - Represents each slot as a ZSET member `token = Base58.randomString(8)` with `score = expiryAtMs`
 *   under the single key `lg:{lockName}`.
 * - All three Lua scripts (ACQUIRE / RELEASE / STATUS) use `redis.call('TIME')` to determine expiry
 *   solely by **Redis server time**, eliminating client clock skew.
 * - At ACQUIRE time, expired entries are automatically reclaimed via `ZREMRANGEBYSCORE 0 nowMs`.
 *   Even if a client crashes without calling release, the slot is cleaned up on the next acquire.
 * - On RELEASE, if `remainingMinLeaseMs > 0` the score is updated via `ZADD XX` to keep the slot alive,
 *   delegating minLeaseTime semantics to the backend TTL (no caller-park).
 * - Slot acquisition spin-polls at 50 ms intervals and returns `null` when the deadline is exceeded.
 *   Acquisition failure returns `null` rather than throwing `IllegalStateException`.
 * - Tokens are held by the caller stack, so the same instance can safely hold multiple slots concurrently.
 *
 * ## Usage
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
 * @param connection Lettuce [StatefulRedisConnection] (StringCodec-based)
 * @param lockName   Slot identifier name (prefixed as `lg:{lockName}`)
 * @param maxLeaders Maximum number of concurrently held slots (>= 1)
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
         * ACQUIRE script.
         *
         * KEYS[1] = slotKey  (lg:{lockName})
         * KEYS[2] = metaKey  (lg:{lockName}:meta)
         * ARGV[1] = maxLeaders
         * ARGV[2] = token (Base58, 8 characters)
         * ARGV[3] = leaseTimeMs
         * ARGV[4] = auditLeaderId (empty string = not set)
         *
         * Returns: token (success) or empty string (failure)
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
         * RELEASE script.
         *
         * KEYS[1] = slotKey  (lg:{lockName})
         * KEYS[2] = metaKey  (lg:{lockName}:meta)
         * ARGV[1] = token
         * ARGV[2] = remainingMinLeaseMs
         *
         * remainingMinLeaseMs > 0 → Updates score via ZADD XX only if the current score is greater than nowMs
         *                            (token still alive), keeping the slot. Prevents resurrection of expired tokens.
         * Otherwise → Immediately releases via ZREM + HDEL
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
         * STATUS script.
         *
         * KEYS[1] = slotKey
         * ARGV[1] = maxLeaders
         *
         * Returns: { active, available }
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
         * EXTEND script (T7 PR 2, AC-16).
         *
         * Uses only server time `redis.call('TIME')` to determine expiry, eliminating client clock skew.
         *
         * KEYS[1] = slotKey  (lg:{lockName})
         * KEYS[2] = metaKey  (lg:{lockName}:meta)
         * ARGV[1] = token (Base58, 8 characters)
         * ARGV[2] = leaseTimeMs
         *
         * Returns:
         *  - `1` : Token is alive (score > nowMs) and score is updated to `nowMs + leaseTimeMs`
         *  - `0` : Token absent or already expired — extend failed
         *
         * Also refreshes the ZSET key TTL via `PEXPIRE k leaseTimeMs + SLOT_KEY_TTL_MARGIN_MS`.
         * metaKey TTL is refreshed at the same time to prevent ghost audit entries.
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
         * IS_HELD script (T7 PR 2).
         *
         * Determines expiry using server-side TIME — eliminates client clock skew.
         *
         * KEYS[1] = slotKey
         * ARGV[1] = token
         *
         * Returns: `1` (alive) / `0` (expired or absent)
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
    val metaKey: String = "$slotKey:meta"

    private val syncCommands: RedisCommands<String, String> = connection.sync()
    private val asyncCommands: RedisAsyncCommands<String, String> = connection.async()

    // =========================================================================
    // 동기 API
    // =========================================================================

    /**
     * Acquires a slot synchronously.
     *
     * @param waitTime      Maximum time to wait for a slot
     * @param leaseTime     Duration to hold the slot
     * @param auditLeaderId Audit identifier (if empty, nothing is recorded in the meta hash)
     * @return Token on success, `null` on failure
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
     * Atomically extends the slot lease (sync, T7 PR 2, AC-16).
     *
     * ## Behavior / Contract
     * - Uses server-side `redis.call('TIME')` to eliminate client clock skew.
     * - If the token's score is less than nowMs (already expired) → [ExtendOutcome.NotHeld]
     * - If score > nowMs → Updates to `nowMs + leaseTime` via ZADD XX and refreshes ZSET key TTL via PEXPIRE → [ExtendOutcome.Extended]
     *
     * **The caller must convert backend exceptions to [ExtendOutcome.BackendError] via try/catch**.
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
     * Checks whether the slot is still alive in the backend (sync).
     *
     * Uses server-side `redis.call('TIME')` to determine expiry — eliminates client clock skew.
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
     * Releases the slot immediately (`remainingMinLeaseMs <= 0`) or keeps it alive by updating only the score.
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
     * Acquires a slot asynchronously.
     *
     * @param waitTime  Maximum time to wait for a slot
     * @param leaseTime Duration to hold the slot (automatically reclaimed on expiry)
     * @return A [CompletableFuture] containing the token on success, or `null` if the deadline is reached due to contention.
     *         If a backend error (Redis connection/auth/script error) is not resolved by the retry deadline,
     *         the future fails with the last error.
     *
     * ### Caveats
     *
     * - Delays between retries use the global single-scheduler thread of [CompletableFuture.delayedExecutor].
     *   This scheduler may become a bottleneck if many concurrent acquires are spin-polling.
     * - Transient script errors (e.g., temporary connection failures) are swallowed within the retry loop
     *   and the next attempt is awaited. However, if the error persists until the deadline (and it is not
     *   just contention), the last error is propagated to the caller. Backend outages are not silently
     *   ignored, consistent with the sync/suspend paths.
     * - The token is generated only once outside `attempt()`. Because the ACQUIRE script uses the token
     *   as a ZADD member, the same token must be reused on retries to avoid ghost entries.
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
     * Atomically extends the slot lease (suspend, T7 PR 2, AC-16).
     *
     * - Although Lettuce `asyncCommands` is Netty event-loop-based non-blocking, the suspend entry point
     *   explicitly checks cancellation via `coroutineContext.ensureActive()` per R9 guidelines.
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
     * Checks whether the slot is still alive in the backend (suspend).
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
