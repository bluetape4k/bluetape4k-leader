package io.bluetape4k.leader.hazelcast.lock

import com.hazelcast.core.HazelcastException
import com.hazelcast.map.IMap
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Distributed lock implementation backed by [IMap].
 *
 * Uses `putIfAbsent(key, token, ttl)` for atomic acquisition and `remove(key, token)` for
 * token-guarded release. Does not depend on thread ID, so it is safe under any execution model
 * including Virtual Threads and thread pools.
 *
 * **Warning:** [leaseTime] must be sufficiently longer than the maximum execution time of the action.
 * If the TTL expires, the lock is released automatically and another node may become leader concurrently.
 *
 * **Warning:** Never enable near-cache on [lockMap].
 * Stale values from near-cache can cause [isHeldByCurrentInstance] to return incorrect results.
 *
 * @param lockMap The [IMap] used to store lock state
 * @param lockKey Key that identifies this lock
 */
class HazelcastLock(
    private val lockMap: IMap<String, String>,
    val lockKey: String,
) {
    companion object: KLogging() {
        private const val RETRY_DELAY_MS = 50L
    }

    private val token: String = Base58.randomString(8)

    /**
     * Attempts to acquire the lock within [waitTime]. Returns `true` on success,
     * or `false` on timeout or cluster error.
     *
     * [HazelcastException] caused by cluster events (partition migration, member departure, etc.)
     * is handled as `false` rather than being propagated, preserving the contract that
     * `runIfLeader()` never throws.
     */
    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds
        val leaseMs = leaseTime.inWholeMilliseconds

        do {
            val previous = try {
                lockMap.putIfAbsent(lockKey, token, leaseMs, TimeUnit.MILLISECONDS)
            } catch (e: HazelcastException) {
                log.warn(e) { "Hazelcast 클러스터 오류로 락 획득 실패: lockKey=$lockKey" }
                return false
            }
            if (previous == null) {
                log.debug { "Lock 획득 성공: lockKey=$lockKey" }
                return true
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0) {
                Thread.sleep(minOf(RETRY_DELAY_MS, remaining))
            }
        } while (System.currentTimeMillis() < deadline)

        log.debug { "Lock 획득 실패 (timeout): lockKey=$lockKey" }
        return false
    }

    /**
     * Checks whether the current instance (token) holds the lock.
     *
     * Returns `true` only when the value stored in [IMap] matches this instance's token.
     */
    fun isHeldByCurrentInstance(): Boolean = lockMap[lockKey] == token

    /**
     * Releases the lock held by the current instance.
     *
     * Validates token ownership before atomically removing the entry.
     * Logs a warning on token mismatch (e.g., the lease expired and another node re-acquired the lock).
     */
    fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) {
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)
        val released = if (remaining > Duration.ZERO) {
            if (isHeldByCurrentInstance()) {
                lockMap.set(lockKey, token, remaining.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                true
            } else {
                false
            }
        } else {
            lockMap.remove(lockKey, token)
        }
        if (released) {
            log.debug { "Lock 해제 성공: lockKey=$lockKey" }
        } else {
            log.warn { "Lock 해제 실패 — 토큰 불일치 (리스 만료 가능성). lockKey=$lockKey" }
        }
    }

    /**
     * Extends the lock's TTL by [leaseTime] in a token-guarded manner and returns an [ExtendOutcome]
     * — T12 PR 7 (Issue #79).
     *
     * ## Behavior / Contract
     * - Step 1: atomically validates that the entry value matches our token using [IMap.replace] (CAS).
     * - Step 2: on token match, updates the TTL with [IMap.setTtl].
     * - Token mismatch or not held → [ExtendOutcome.NotHeld]
     * - Successful renewal → [ExtendOutcome.Extended] (`observedExpireAt = now + leaseTime` — best-effort)
     * - [HazelcastException] → [ExtendOutcome.BackendError]
     *
     * ## R6 — Expired-Entry Revival Prevention
     * Hazelcast IMap automatically evicts entries on TTL expiry, so [IMap.replace] returns `false`
     * for expired entries → NotHeld.
     *
     * ## Design Note — Why replace+setTtl Instead of EntryProcessor
     * In Hazelcast client-server mode, a custom `EntryProcessor` requires class deployment to the
     * server JVM ([UserCodeDeployment] or pre-deployment on the server side). The Hazelcast server in
     * bluetape4k-testcontainers uses a vanilla image, so the class cannot be found and a
     * `HazelcastSerializationException(ClassNotFoundException)` is thrown.
     * Instead, the same token-guard semantics are achieved using built-in atomic primitives
     * ([IMap.replace] CAS + [IMap.setTtl]).
     *
     * ## Race Window (Acceptable)
     * An entry may expire during the sub-millisecond window between `replace` and `setTtl`.
     * In that case `setTtl` returns `false` → treated as NotHeld.
     * A theoretical race exists where our `setTtl` arrives after another instance takes over and
     * refreshes that instance's TTL, but this window is extremely narrow — immediately after `replace`
     * passes token validation.
     */
    fun extendDetailed(leaseTime: Duration): ExtendOutcome {
        val leaseMs = leaseTime.inWholeMilliseconds
        val nowMs = System.currentTimeMillis()
        return try {
            // 1) CAS — value 가 우리 토큰일 때만 (no-op replace) 성공
            val matched = lockMap.replace(lockKey, token, token)
            if (!matched) {
                log.debug { "Hazelcast extend NotHeld (token mismatch / 만료): lockKey=$lockKey" }
                ExtendOutcome.NotHeld
            } else {
                // 2) TTL 갱신
                val updated = lockMap.setTtl(lockKey, leaseMs, TimeUnit.MILLISECONDS)
                if (updated) {
                    ExtendOutcome.Extended(Instant.ofEpochMilli(nowMs + leaseMs))
                } else {
                    log.debug { "Hazelcast extend NotHeld (setTtl 실패 — race window): lockKey=$lockKey" }
                    ExtendOutcome.NotHeld
                }
            }
        } catch (e: HazelcastException) {
            ExtendOutcome.BackendError(e)
        }
    }
}
