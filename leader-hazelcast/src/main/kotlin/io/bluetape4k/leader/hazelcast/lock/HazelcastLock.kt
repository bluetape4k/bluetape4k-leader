package io.bluetape4k.leader.hazelcast.lock

import com.hazelcast.core.HazelcastException
import com.hazelcast.map.IMap
import com.hazelcast.transaction.TransactionContext
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
    private val transactionMapName: String? = null,
    private val transactionContextProvider: (() -> TransactionContext)? = null,
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
     * Validates token ownership in a Hazelcast transaction before rewriting TTL or removing the entry.
     * Logs a warning on token mismatch (e.g., the lease expired and another node re-acquired the lock).
     */
    fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) {
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)
        val released = withOwnedTransaction(
            onTransactionUnavailable = { releaseDirectly(remaining) },
            onNotHeld = { false },
        ) { txMap ->
            if (remaining > Duration.ZERO) {
                txMap.put(lockKey, token, remaining.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                true
            } else {
                txMap.remove(lockKey)
                true
            }
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
     * - Step 1: opens a Hazelcast transaction and reads [lockKey] with `getForUpdate`.
     * - Step 2: validates that the entry value still matches our token.
     * - Step 3: on token match, rewrites the same token with a new TTL via transactional `put`.
     * - Token mismatch or not held → [ExtendOutcome.NotHeld]
     * - Successful renewal → [ExtendOutcome.Extended] (`observedExpireAt = now + leaseTime` — best-effort)
     * - [HazelcastException] → [ExtendOutcome.BackendError]
     *
     * ## R6 — Expired-Entry Revival Prevention
     * Hazelcast IMap automatically evicts entries on TTL expiry, so [IMap.replace] returns `false`
     * for expired entries → NotHeld.
     *
     * ## Design Note — Why Transaction Instead of EntryProcessor
     * In Hazelcast client-server mode, a custom `EntryProcessor` requires class deployment to the
     * server JVM ([UserCodeDeployment] or pre-deployment on the server side). The Hazelcast server in
     * bluetape4k-testcontainers uses a vanilla image, so the class cannot be found and a
     * `HazelcastSerializationException(ClassNotFoundException)` is thrown.
     * Instead, token validation and TTL rewrite use Hazelcast's built-in transactional map API,
     * so a stale owner cannot refresh a successor entry between validation and TTL update.
     */
    fun extendDetailed(leaseTime: Duration): ExtendOutcome {
        val leaseMs = leaseTime.inWholeMilliseconds
        val nowMs = System.currentTimeMillis()
        return try {
            withOwnedTransaction(
                onTransactionUnavailable = { extendDirectly(leaseMs, nowMs) },
                onNotHeld = {
                    log.debug { "Hazelcast extend NotHeld (token mismatch / 만료): lockKey=$lockKey" }
                    ExtendOutcome.NotHeld
                },
            ) {
                it.put(lockKey, token, leaseMs, TimeUnit.MILLISECONDS)
                ExtendOutcome.Extended(Instant.ofEpochMilli(nowMs + leaseMs))
            }
        } catch (e: HazelcastException) {
            ExtendOutcome.BackendError(e)
        }
    }

    private inline fun <T> withOwnedTransaction(
        onTransactionUnavailable: () -> T,
        onNotHeld: () -> T,
        block: (com.hazelcast.transaction.TransactionalMap<String, String>) -> T,
    ): T {
        val provider = transactionContextProvider ?: return onTransactionUnavailable()
        val context = provider()
        context.beginTransaction()
        return try {
            val txMap = context.getMap<String, String>(transactionMapName ?: lockMap.name)
            if (txMap.getForUpdate(lockKey) != token) {
                context.commitTransaction()
                onNotHeld()
            } else {
                val result = block(txMap)
                context.commitTransaction()
                result
            }
        } catch (e: Throwable) {
            runCatching { context.rollbackTransaction() }
            throw e
        }
    }

    private fun releaseDirectly(remaining: Duration): Boolean {
        if (lockMap[lockKey] != token) {
            return false
        }
        return if (remaining > Duration.ZERO) {
            lockMap.set(lockKey, token, remaining.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            true
        } else {
            lockMap.remove(lockKey, token)
        }
    }

    private fun extendDirectly(leaseMs: Long, nowMs: Long): ExtendOutcome {
        if (lockMap[lockKey] != token) {
            log.debug { "Hazelcast extend NotHeld (token mismatch / 만료): lockKey=$lockKey" }
            return ExtendOutcome.NotHeld
        }
        val updated = lockMap.setTtl(lockKey, leaseMs, TimeUnit.MILLISECONDS)
        return if (updated) {
            ExtendOutcome.Extended(Instant.ofEpochMilli(nowMs + leaseMs))
        } else {
            log.debug { "Hazelcast extend NotHeld (setTtl 실패): lockKey=$lockKey" }
            ExtendOutcome.NotHeld
        }
    }
}
