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
 * `HazelcastLock`는 Hazelcast backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property lockMap Hazelcast backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockKey Hazelcast backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property transactionMapName Hazelcast backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property transactionContextProvider Hazelcast backend 호출과 상태 계산에 사용하는 속성입니다.
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
     * `tryLock` 호출은 Hazelcast backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
     * `isHeldByCurrentInstance` 호출은 Hazelcast backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun isHeldByCurrentInstance(): Boolean = lockMap[lockKey] == token

    /**
     * `unlock` 호출은 Hazelcast backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
     * `extendDetailed` 호출은 Hazelcast backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
