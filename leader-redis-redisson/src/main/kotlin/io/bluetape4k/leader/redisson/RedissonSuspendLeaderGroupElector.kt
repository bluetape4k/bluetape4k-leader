package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.redisson.internal.RedissonBackendErrorClassifier
import io.bluetape4k.leader.redisson.internal.RedissonSuspendSemaphoreExtendDelegate
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.redisson.api.RPermitExpirableSemaphore
import org.redisson.api.RedissonClient
import org.redisson.client.RedisException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Redisson 분산 [RPermitExpirableSemaphore]를 이용한 코루틴 복수 리더 선출 구현체입니다.
 *
 * ## 동작/계약 (T8 PR 3)
 *
 * - `lockName` 별로 `lg:{lockName}` Redisson [RPermitExpirableSemaphore] 를 사용합니다.
 * - 슬롯 획득 실패 시 `null` 반환 (ShedLock skip-on-contention).
 * - `options.minLeaseTime > 0` 이면 빠른 action 종료 시 `updateLeaseTimeAsync` 로
 *   slot 의 TTL 을 minLeaseTime 만큼 연장하여 유지합니다 (caller-park 없음).
 * - 코루틴 취소 시에도 `withContext(NonCancellable)` 안에서 release 가 보장됩니다.
 * - `CancellationException` 은 항상 re-throw 합니다.
 *
 * ## ExtendDelegate 통합
 *
 * - acquire 후 [RedissonSuspendSemaphoreExtendDelegate] 를 생성하여 [LeaderLockHandle.Real] + watchdog 와 동일 reference 공유 (AC-15).
 * - `withContext(AopScopeAccess.createLockHandleElement(handle))` + `setCapture` 로 coroutineContext 와 ThreadLocal 양쪽에 handle 전파.
 *
 * ```kotlin
 * val options = LeaderGroupElectionOptions(maxLeaders = 3, minLeaseTime = 1.seconds)
 * val election = RedissonSuspendLeaderGroupElector(redissonClient, options)
 * val result = election.runIfLeader("batch-job") { processChunkSuspend() }
 * ```
 *
 * @param redissonClient Redisson 클라이언트
 * @param options 리더 선출 옵션 (maxLeaders, waitTime, leaseTime, minLeaseTime)
 */
class RedissonSuspendLeaderGroupElector private constructor(
    private val redissonClient: RedissonClient,
    val options: LeaderGroupElectionOptions,
): SuspendLeaderGroupElector {

    companion object: KLoggingChannel() {
        internal const val REDISSON_SUSPEND_GROUP_FACTORY_BEAN_NAME = "redisson-suspend-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(RedissonBackendErrorClassifier)

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
     * Codex P1: `trySetPermits(maxLeaders)` 멱등 호출 — 누락 시 acquire 영구 실패.
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

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
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

        val delegate = RedissonSuspendSemaphoreExtendDelegate(semaphore, permitId)
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
        )
        // Group elector: autoExtend 옵션 부재 — caller 가 LockExtender 로 명시적 연장. watchdog disabled.
        val watchdog = LeaderLeaseAutoExtender.start(false, options.leaseTime, delegate, ERROR_CLASSIFIER)

        try {
            // setCapture: ThreadLocal capture 도 함께 push (aspect dual-source: ThreadLocal + coroutineContext)
            AopScopeAccess.setCapture(handle)
            try {
                return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                    action()
                }
            } finally {
                AopScopeAccess.clearCapture()
            }
        } finally {
            // NonCancellable: 코루틴 취소 시에도 release/extend 가 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog.close()
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
 * Redisson 분산 [RPermitExpirableSemaphore] 를 이용하여 복수 리더 선출을 통한 suspend 작업을 수행합니다.
 *
 * ```kotlin
 * val client: RedissonClient = ...
 * val options = LeaderGroupElectionOptions(maxLeaders = 3)
 * val result: Int = client.runSuspendIfLeaderGroup("batch-job", options) {
 *     delay(100)
 *     42
 * }
 * ```
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
