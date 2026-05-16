package io.bluetape4k.leader.hazelcast.lock

import com.hazelcast.core.HazelcastException
import com.hazelcast.map.IMap
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coroutine implementation of an [IMap]-based distributed lock.
 *
 * Wraps `putIfAbsent(key, token, ttl)` / `remove(key, token)` with `withContext(Dispatchers.IO)`
 * to expose them as suspend functions. Token-based, so it is safe regardless of coroutine thread switches.
 *
 * **Warning:** [leaseTime] must be sufficiently larger than the maximum execution time of the action.
 * If the TTL expires, the lock is automatically released and another node may become leader concurrently.
 *
 * **Warning:** Never enable near-cache on [lockMap].
 * Stale values from near-cache can cause [isHeldByCurrentInstance] to return incorrect results.
 *
 * @param lockMap [IMap] used to store lock state
 * @param lockKey Lock identification key
 */
class HazelcastSuspendLock(
    private val lockMap: IMap<String, String>,
    val lockKey: String,
) {
    companion object: KLoggingChannel() {
        private const val RETRY_DELAY_MS = 50L
    }

    private val token: String = Base58.randomString(8)

    /**
     * Attempts to acquire the lock within [waitTime]. Returns `true` on success, `false` on timeout or cluster error.
     *
     * Executes the blocking `putIfAbsent` on `Dispatchers.IO` and suspends via `delay` during retries.
     * [HazelcastException] caused by Hazelcast cluster events is treated as `false` to guarantee
     * that `runIfLeader()` never throws.
     */
    suspend fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds
        val leaseMs = leaseTime.inWholeMilliseconds

        do {
            val previous = try {
                withContext(Dispatchers.IO) {
                    lockMap.putIfAbsent(lockKey, token, leaseMs, TimeUnit.MILLISECONDS)
                }
            } catch (e: HazelcastException) {
                log.warn(e) { "Hazelcast 클러스터 오류로 락 획득 실패 (suspend): lockKey=$lockKey" }
                return false
            }
            if (previous == null) {
                log.debug { "Lock 획득 성공 (suspend): lockKey=$lockKey" }
                return true
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0) {
                delay(minOf(RETRY_DELAY_MS, remaining).milliseconds)
            }
        } while (System.currentTimeMillis() < deadline)

        log.debug { "Lock 획득 실패 (timeout, suspend): lockKey=$lockKey" }
        return false
    }

    /**
     * Checks whether the current instance (token) holds the lock.
     *
     * Returns `true` only if the value stored in the [IMap] matches this instance's token.
     */
    suspend fun isHeldByCurrentInstance(): Boolean = withContext(Dispatchers.IO) {
        lockMap[lockKey] == token
    }

    /**
     * Releases the lock held by the current instance.
     *
     * Verifies token ownership and then removes it atomically.
     * Logs a warning if the token does not match (e.g., the lease expired and another node re-acquired the lock).
     */
    suspend fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) = withContext(Dispatchers.IO) {
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)
        val released = if (remaining > Duration.ZERO) {
            if (lockMap[lockKey] == token) {
                lockMap.set(lockKey, token, remaining.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                true
            } else {
                false
            }
        } else {
            lockMap.remove(lockKey, token)
        }
        if (released) {
            log.debug { "Lock 해제 성공 (suspend): lockKey=$lockKey" }
        } else {
            log.warn { "Lock 해제 실패 — 토큰 불일치 (리스 만료 가능성, suspend). lockKey=$lockKey" }
        }
    }

    /**
     * Atomically extends the lock TTL by [leaseTime] and returns an [ExtendOutcome] (suspend) — T12 PR 7 (Issue #79).
     *
     * Since Hazelcast IMap is a blocking API, it is wrapped with `withContext(Dispatchers.IO)` to suspend.
     * Behavior and contract are identical to [HazelcastLock.extendDetailed] (R6 — IMap auto-evict blocks expired-doc revival).
     */
    suspend fun extendDetailed(leaseTime: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        val leaseMs = leaseTime.inWholeMilliseconds
        val nowMs = System.currentTimeMillis()
        try {
            // 1) CAS — value 가 우리 토큰일 때만 (no-op replace) 성공
            val matched = lockMap.replace(lockKey, token, token)
            if (!matched) {
                log.debug { "Hazelcast extend NotHeld (suspend): lockKey=$lockKey" }
                ExtendOutcome.NotHeld
            } else {
                val updated = lockMap.setTtl(lockKey, leaseMs, TimeUnit.MILLISECONDS)
                if (updated) {
                    ExtendOutcome.Extended(Instant.ofEpochMilli(nowMs + leaseMs))
                } else {
                    log.debug { "Hazelcast extend NotHeld (setTtl 실패 — race, suspend): lockKey=$lockKey" }
                    ExtendOutcome.NotHeld
                }
            }
        } catch (e: HazelcastException) {
            ExtendOutcome.BackendError(e)
        }
    }
}
