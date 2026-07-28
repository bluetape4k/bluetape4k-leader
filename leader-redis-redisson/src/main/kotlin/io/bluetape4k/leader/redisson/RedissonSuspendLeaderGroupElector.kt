package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.redisson.internal.RedissonBackendErrorClassifier
import io.bluetape4k.leader.redisson.internal.RedissonSuspendSemaphoreExtendDelegate
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.redisson.api.RMap
import org.redisson.api.RPermitExpirableSemaphore
import org.redisson.api.RedissonClient
import org.redisson.client.RedisException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * `RedissonSuspendLeaderGroupElector`는 Redis Redisson backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property redissonClient Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class RedissonSuspendLeaderGroupElector private constructor(
    private val redissonClient: RedissonClient,
    val options: LeaderGroupElectionOptions,
): SuspendLeaderGroupElector {

    companion object: KLoggingChannel() {
        internal const val REDISSON_SUSPEND_GROUP_FACTORY_BEAN_NAME = "redisson-suspend-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(RedissonBackendErrorClassifier)
        private const val AUDIT_MAP_TTL_PADDING_MS = 5_000L

        operator fun invoke(
            redissonClient: RedissonClient,
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
        ): RedissonSuspendLeaderGroupElector {
            options.maxLeaders.requirePositiveNumber("maxLeaders")
            return RedissonSuspendLeaderGroupElector(redissonClient, options)
        }
    }

    override val maxLeaders: Int = options.maxLeaders
    private val waitTime: Duration = options.waitTime
    private val leaseTime: Duration = options.leaseTime

    /**
     * `getInitializedPermitSemaphore` 호출은 Redis Redisson backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    private fun getInitializedPermitSemaphore(lockName: String): RPermitExpirableSemaphore {
        lockName.requireNotBlank("lockName")
        val semaphore = redissonClient.getPermitExpirableSemaphore("lg:{$lockName}")
        semaphore.trySetPermits(maxLeaders)
        return semaphore
    }

    private suspend fun getInitializedPermitSemaphoreAsync(lockName: String): RPermitExpirableSemaphore {
        lockName.requireNotBlank("lockName")
        val semaphore = redissonClient.getPermitExpirableSemaphore("lg:{$lockName}")
        semaphore.trySetPermitsAsync(maxLeaders).await()
        return semaphore
    }

    override fun activeCount(lockName: String): Int =
        maxLeaders - getInitializedPermitSemaphore(lockName).availablePermits()

    override fun availableSlots(lockName: String): Int =
        getInitializedPermitSemaphore(lockName).availablePermits()

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        runImpl(lockName, auditLeaderId = null, action)

    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        runImpl(slot.lockName, auditLeaderId = slot.leaderId, action)

    override suspend fun <T> runIfLeaderResultSuspend(slot: LeaderSlot, action: suspend () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runImpl(slot.lockName, auditLeaderId = slot.leaderId) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    private fun getAuditMap(lockName: String): RMap<String, String> =
        redissonClient.getMap("lg:{$lockName}:audit")

    private suspend fun <T> runImpl(lockName: String, auditLeaderId: String?, action: suspend () -> T): T? {
        lockName.requireNotBlank("lockName")

        val semaphore = getInitializedPermitSemaphoreAsync(lockName)
        log.debug { "슬롯 획득 요청. lockName=$lockName, maxLeaders=$maxLeaders" }

        val permitId: String? = try {
            semaphore.tryAcquireAsync(
                waitTime.inWholeMilliseconds,
                leaseTime.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            ).await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(e) { "슬롯 획득 대기 중 인터럽트. lockName=$lockName" }
            throw RedisException("Interrupted while acquiring permit. lockName=$lockName", e)
        }

        if (permitId == null) {
            log.debug { "슬롯 획득 실패. lockName=$lockName" }
            return null
        }
        // Codex P2: acquire 성공 후 startedAtNanos 캡처
        val startedAtNanos = System.nanoTime()
        log.debug { "슬롯 획득 성공. lockName=$lockName, permitId=$permitId" }

        // 감사 추적용 RMap 기록 (non-atomic, 트레이서빌리티 전용)
        val auditMap = getAuditMap(lockName)
        if (auditLeaderId != null) {
            try {
                withContext(Dispatchers.IO) {
                    auditMap.fastPut(permitId, auditLeaderId)
                    auditMap.expire(java.time.Duration.ofMillis(leaseTime.inWholeMilliseconds + AUDIT_MAP_TTL_PADDING_MS))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Failed to write audit map. lockName=$lockName, permitId=$permitId" }
            }
        }

        val delegate: SuspendExtendDelegate = RedissonSuspendSemaphoreExtendDelegate(semaphore, permitId)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = REDISSON_SUSPEND_GROUP_FACTORY_BEAN_NAME,
            groupParams = LockIdentity.GroupParams(maxLeaders),
        )
        val handle = LeaderLockHandle.real(
            identity = identity,
            token = permitId,
            acquiredAtNanos = startedAtNanos,
            slotId = permitId,
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId,
        )
        // Group elector: autoExtend 옵션 부재 — caller 가 LockExtender 로 명시적 연장. watchdog disabled.
        val watchdog = LeaderLeaseAutoExtender.start(false, options.leaseTime, delegate, ERROR_CLASSIFIER)

        try {
            return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
        } finally {
            // NonCancellable: 코루틴 취소 시에도 release/extend 가 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog.close()
                if (auditLeaderId != null) {
                    try {
                        withContext(Dispatchers.IO) { auditMap.fastRemove(permitId) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn(e) { "Failed to remove audit map. lockName=$lockName, permitId=$permitId" }
                    }
                }
                try {
                    val remainingMs = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime).inWholeMilliseconds
                    if (remainingMs > 0) {
                        semaphore.updateLeaseTimeAsync(permitId, remainingMs, TimeUnit.MILLISECONDS).await()
                        log.debug {
                            "minLease 유지를 위해 leaseTime 갱신. lockName=$lockName, permitId=$permitId, remainingMs=$remainingMs"
                        }
                    } else {
                        semaphore.releaseAsync(permitId).await()
                        log.debug { "슬롯 즉시 반납. lockName=$lockName, permitId=$permitId" }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.warn(e) { "Failed to release/extend permit. lockName=$lockName, permitId=$permitId" }
                }
            }
        }
    }
}

/**
 * `선언` 호출은 Redis Redisson backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend fun <T> RedissonClient.runSuspendIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? {
    lockName.requireNotBlank("lockName")
    options.maxLeaders.requirePositiveNumber("maxLeaders")
    return RedissonSuspendLeaderGroupElector(this, options).runIfLeader(lockName, action)
}
