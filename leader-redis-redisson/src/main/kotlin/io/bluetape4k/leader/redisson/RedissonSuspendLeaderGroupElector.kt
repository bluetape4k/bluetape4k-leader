package io.bluetape4k.leader.redisson

import io.bluetape4k.javatimes.millis
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
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
import io.bluetape4k.leader.redisson.runSuspendIfLeaderGroup as currentRunSuspendIfLeaderGroup

/**
 * `RedissonSuspendLeaderGroupElector`는 Redis Redisson의 suspend 그룹 선출을 제공하며 `maxLeaders` 슬롯을 acquire·extend·release합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property redissonClient `lg:{$lockName}` async permit-expirable semaphore와 audit map을 만드는 Redisson client입니다.
 * @property options 동시에 허용할 permit 수(`maxLeaders`), 대기·lease·최소 lease 기간을 정하는 그룹 정책입니다.
 */
class RedissonSuspendLeaderGroupElector private constructor(
    private val redissonClient: RedissonClient,
    val options: LeaderGroupElectionOptions,
): SuspendLeaderGroupElector,
   LeaderBackendDiagnosticsProvider by RedissonLeaderBackendDiagnostics(redissonClient) {

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

    /** `lg:{$lockName}` semaphore를 조회하고 async 호출로 `maxLeaders` permit 초기화를 시도합니다. */
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

    private suspend fun <T> runImpl(
        lockName: String,
        auditLeaderId: String?,
        action: suspend () -> T,
    ): T? {
        lockName.requireNotBlank("lockName")

        val semaphore = getInitializedPermitSemaphoreAsync(lockName)
        log.debug { "슬롯 획득 요청... lockName=$lockName, maxLeaders=$maxLeaders" }

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
                    auditMap.expire((leaseTime.inWholeMilliseconds + AUDIT_MAP_TTL_PADDING_MS).millis())
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
                LeaderLeaseAutoExtender.closeSuspend(watchdog)
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
                            "minLease 유지를 위해 leaseTime 갱신. " +
                                    "lockName=$lockName, permitId=$permitId, remainingMs=$remainingMs"
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

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("runSuspendIfLeaderGroup")
suspend fun <T> RedissonClient.legacyRunSuspendIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? = this.currentRunSuspendIfLeaderGroup(lockName, options, action)
