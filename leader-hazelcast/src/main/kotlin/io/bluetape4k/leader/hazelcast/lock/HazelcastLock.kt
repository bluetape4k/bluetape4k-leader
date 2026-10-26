package io.bluetape4k.leader.hazelcast.lock

import com.hazelcast.core.HazelcastException
import com.hazelcast.map.IMap
import com.hazelcast.transaction.TransactionContext
import io.bluetape4k.ToStringBuilder
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.MonotonicDeadline
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * `HazelcastLock`는 Hazelcast lock key의 token 기반 tryLock·ownership 확인·unlock·lease 연장을 blocking 경로로 수행합니다.
 *
 * `tryLock`이 경합·timeout으로 ownership을 획득하지 못하면 `false`를 반환하며, unlock/extend는 backend token 일치 여부를 확인해 불일치·만료를
 * 성공으로 처리하지 않습니다.
 * @property lockMap lock key와 소유 token을 저장하며 TTL을 적용하는 Hazelcast `IMap`입니다.
 * @property lockKey `lockMap`에서 이 인스턴스의 소유 token을 조회할 문자열 키입니다. 그룹 선출에서는 슬롯 키가 됩니다.
 * @property transactionMapName transaction-aware 연산에서 사용할 map 이름이며, `null`이면 `lockMap.name`을 사용합니다.
 * @property transactionContextProvider lock 해제·연장 시 소유 검증을 원자적으로 수행할 transaction context 공급자입니다. `null`이면 직접
 * map 연산을 사용합니다.
 */
class HazelcastLock(
    private val lockMap: IMap<String, String>,
    val lockKey: String,
    private val transactionMapName: String? = null,
    private val transactionContextProvider: (() -> TransactionContext)? = null,
) {
    /**
     * Preserves the two-argument JVM constructor published before transaction-aware
     * unlock support was added.
     */
    constructor(lockMap: IMap<String, String>, lockKey: String): this(
        lockMap = lockMap,
        lockKey = lockKey,
        transactionMapName = null,
        transactionContextProvider = null,
    )

    companion object: KLogging() {
        private const val RETRY_DELAY_MS = 50L
    }

    private val token: String = Base58.randomString(8)

    /** `waitTime` 동안 최대 50ms 간격으로 `putIfAbsent`를 재시도하고, 성공 시 `leaseTime` TTL을 설정합니다. 경합·오류·시간 초과는 `false`입니다. */
    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean = tryLock(
        waitTime = waitTime,
        leaseTime = leaseTime,
        ticker = System::nanoTime,
        sleep = Thread::sleep,
    )

    @JvmSynthetic
    @Suppress("ReturnCount")
    internal fun tryLock(
        waitTime: Duration,
        leaseTime: Duration,
        ticker: () -> Long,
        sleep: (Long) -> Unit,
    ): Boolean {
        val deadline = MonotonicDeadline.fromNow(waitTime, ticker)
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
            val remaining = deadline.remainingMillisForDelay(RETRY_DELAY_MS)
            if (remaining > 0) {
                sleep(remaining)
            }
        } while (deadline.hasTimeRemaining())

        log.debug { "Lock 획득 실패 (timeout): lockKey=$lockKey" }
        return false
    }

    /** map의 현재 값이 이 인스턴스가 발급한 소유 token과 같은지 비교합니다. */
    fun isHeldByCurrentInstance(): Boolean = lockMap[lockKey] == token

    /** 소유 token을 검증한 뒤, 남은 `minLeaseTime`이 있으면 TTL만 줄이고 없으면 key를 제거합니다. */
    fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) {
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)
        val released = withOwnedTransaction(
            onTransactionUnavailable = { releaseDirectly(remaining) },
            onNotHeld = { false },
        ) { txMap ->
            if (remaining.isPositive()) {
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

    /** 소유 token이 아직 유효할 때만 TTL을 `leaseTime`으로 갱신해 `Extended`를 반환하며, 불일치·만료는 `NotHeld`입니다. */
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
        return if (remaining.isPositive()) {
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

    override fun toString(): String {
        return ToStringBuilder(this)
            .add("lockKey", lockKey)
            .add("transactionMapName", transactionMapName)
            .add("lockMap", lockMap)
            .toString()
    }
}
