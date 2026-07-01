package io.bluetape4k.leader.redisson

import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.redisson.internal.RedissonBackendErrorClassifier
import io.bluetape4k.leader.redisson.internal.RedissonSemaphoreExtendDelegate
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.redisson.api.RMap
import org.redisson.api.RPermitExpirableSemaphore
import org.redisson.api.RedissonClient
import org.redisson.client.RedisException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * Multi-leader election implementation using Redisson distributed [RPermitExpirableSemaphore].
 *
 * ## Behavior / Contract (T8 PR 3)
 *
 * - Uses a Redisson [RPermitExpirableSemaphore] keyed as `lg:{lockName}` per `lockName`.
 * - Each acquire returns a unique permitId; the exact slot is identified by permitId on release.
 * - If `options.minLeaseTime > 0` and the action completes quickly, the slot TTL is extended by minLeaseTime
 *   via [RPermitExpirableSemaphore.updateLeaseTime] (no caller-park).
 * - On client crash (release not called), Redisson automatically reclaims the slot after leaseTime expires.
 * - Returns `null` when a slot cannot be acquired (ShedLock skip-on-contention).
 *
 * ## ExtendDelegate Integration
 *
 * - After acquire, creates a [RedissonSemaphoreExtendDelegate] shared with [LeaderLockHandle.Real] and the watchdog under the same reference (AC-15).
 * - When the aspect calls `LockExtender.extendActiveLock`, the same delegate executes `RPermitExpirableSemaphore.updateLeaseTime`.
 * - Pushes the handle into both `LockStateHolder` and `LeaderLockHandleCapture` (via AopScopeAccess).
 *
 * ```kotlin
 * val options = LeaderGroupElectionOptions(maxLeaders = 3, minLeaseTime = 1.seconds)
 * val election = RedissonLeaderGroupElector(redissonClient, options)
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * @param redissonClient Redisson client
 * @param options Leader election options (maxLeaders, waitTime, leaseTime, minLeaseTime)
 */
class RedissonLeaderGroupElector private constructor(
    private val redissonClient: RedissonClient,
    val options: LeaderGroupElectionOptions,
): LeaderGroupElector {
    companion object: KLogging() {
        internal const val REDISSON_GROUP_FACTORY_BEAN_NAME = "redisson-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(RedissonBackendErrorClassifier)
        private const val AUDIT_MAP_TTL_PADDING_MS = 5_000L

        @JvmStatic
        operator fun invoke(
            redissonClient: RedissonClient,
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
        ): RedissonLeaderGroupElector {
            options.maxLeaders.requirePositiveNumber("maxLeaders")
            return RedissonLeaderGroupElector(redissonClient, options)
        }
    }

    override val maxLeaders: Int = options.maxLeaders

    private val waitTime: Duration = options.waitTime
    private val leaseTime: Duration = options.leaseTime

    /**
     * Returns the [RPermitExpirableSemaphore] for the `lg:{lockName}` key.
     *
     * Codex P1: Idempotent `trySetPermits(maxLeaders)` call — omitting it causes acquire to fail permanently with 0 permits.
     */
    private fun getPermitSemaphore(lockName: String): RPermitExpirableSemaphore {
        lockName.requireNotBlank("lockName")
        val semaphore = redissonClient.getPermitExpirableSemaphore("lg:{$lockName}")
        semaphore.trySetPermits(maxLeaders)
        return semaphore
    }

    override fun activeCount(lockName: String): Int =
        maxLeaders - getPermitSemaphore(lockName).availablePermits()

    override fun availableSlots(lockName: String): Int =
        getPermitSemaphore(lockName).availablePermits()

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        runImpl(lockName, auditLeaderId = null, action)

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        runImpl(slot.lockName, auditLeaderId = slot.leaderId, action)

    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runImpl(slot.lockName, auditLeaderId = slot.leaderId) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
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

    private fun <T> runImpl(lockName: String, auditLeaderId: String?, action: () -> T): T? {
        lockName.requireNotBlank("lockName")
        val semaphore = getPermitSemaphore(lockName)
        log.debug { "리더 그룹 슬롯 획득 요청. lockName=$lockName, maxLeaders=$maxLeaders" }

        val permitId: String? = try {
            semaphore.tryAcquire(
                waitTime.inWholeMilliseconds,
                leaseTime.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.error(e) { "슬롯 획득 대기 중 인터럽트. lockName=$lockName" }
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
            runCatching {
                auditMap.fastPut(permitId, auditLeaderId)
                auditMap.expire(java.time.Duration.ofMillis(leaseTime.inWholeMilliseconds + AUDIT_MAP_TTL_PADDING_MS))
            }.onFailure { log.warn(it) { "Failed to write audit map. lockName=$lockName, permitId=$permitId" } }
        }

        val delegate = RedissonSemaphoreExtendDelegate(semaphore, permitId)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = REDISSON_GROUP_FACTORY_BEAN_NAME,
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
            return AopScopeAccess.withPushedSync(handle) {
                AopScopeAccess.setCapture(handle)
                try {
                    action()
                } finally {
                    AopScopeAccess.clearCapture()
                }
            }
        } finally {
            watchdog.close()
            if (auditLeaderId != null) {
                runCatching { auditMap.fastRemove(permitId) }
                    .onFailure { log.warn(it) { "Failed to remove audit map. lockName=$lockName, permitId=$permitId" } }
            }
            releaseOrExtend(semaphore, permitId, startedAtNanos, lockName)
        }
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        runAsyncImpl(lockName, auditLeaderId = null, executor, action)

    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        runAsyncImpl(slot.lockName, auditLeaderId = slot.leaderId, executor, action)

    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        val elected = AtomicBoolean(false)
        return runAsyncIfLeader(slot, executor) {
            elected.set(true)
            action()
        }.handle { value, failure ->
            when {
                failure != null && elected.get() -> failure.toActionFailedResult()
                failure != null -> throw failure.asCompletionException()
                elected.get() -> LeaderRunResult.Elected(value, leaderId = slot.leaderId)
                else -> LeaderRunResult.Skipped
            }
        }
    }

    private fun <T> runAsyncImpl(
        lockName: String,
        auditLeaderId: String?,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        return try {
            lockName.requireNotBlank("lockName")
            val semaphore = getPermitSemaphore(lockName)
            log.debug { "리더 그룹 슬롯 획득 요청 (async). lockName=$lockName" }

            semaphore
                .tryAcquireAsync(
                    waitTime.inWholeMilliseconds,
                    leaseTime.inWholeMilliseconds,
                    TimeUnit.MILLISECONDS,
                )
                .toCompletableFuture()
                .thenComposeAsync({ permitId ->
                    if (permitId == null) {
                        log.debug { "슬롯 획득 실패 (async). lockName=$lockName" }
                        CompletableFuture.completedFuture<T?>(null)
                    } else {
                        // Codex P2: acquire 성공 후 startedAtNanos 캡처
                        val startedAtNanos = System.nanoTime()
                        executeAsync(semaphore, lockName, permitId, auditLeaderId, startedAtNanos, action)
                    }
                }, executor)
        } catch (e: Throwable) {
            log.error(e) { "Fail to runAsync as Leader Group. lockName=$lockName" }
            failedCompletableFutureOf(e)
        }
    }

    private fun <T> executeAsync(
        semaphore: RPermitExpirableSemaphore,
        lockName: String,
        permitId: String,
        auditLeaderId: String?,
        startedAtNanos: Long,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        log.debug { "슬롯 획득 성공 (async). lockName=$lockName, permitId=$permitId" }

        val auditMap = getAuditMap(lockName)
        if (auditLeaderId != null) {
            runCatching {
                auditMap.fastPut(permitId, auditLeaderId)
                auditMap.expire(java.time.Duration.ofMillis(leaseTime.inWholeMilliseconds + AUDIT_MAP_TTL_PADDING_MS))
            }.onFailure { log.warn(it) { "Failed to write audit map. lockName=$lockName, permitId=$permitId" } }
        }

        val delegate = RedissonSemaphoreExtendDelegate(semaphore, permitId)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = REDISSON_GROUP_FACTORY_BEAN_NAME,
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

        val actionFuture: CompletableFuture<T> = try {
            AopScopeAccess.withPushedSync(handle) {
                AopScopeAccess.setCapture(handle)
                try {
                    action()
                } finally {
                    AopScopeAccess.clearCapture()
                }
            }
        } catch (e: Throwable) {
            removeAuditEntry(auditMap, permitId, auditLeaderId, lockName)
            return releaseAndPropagate(semaphore, permitId, startedAtNanos, lockName, e, null)
        }

        return actionFuture
            .handle<Pair<T?, Throwable?>> { value, error -> Pair(value, error) }
            .thenCompose { (value, error) ->
                removeAuditEntry(auditMap, permitId, auditLeaderId, lockName)
                releaseAndPropagate(semaphore, permitId, startedAtNanos, lockName, error, value)
            }
    }

    private fun removeAuditEntry(
        auditMap: RMap<String, String>,
        permitId: String,
        auditLeaderId: String?,
        lockName: String,
    ) {
        if (auditLeaderId != null) {
            runCatching { auditMap.fastRemove(permitId) }
                .onFailure { log.warn(it) { "Failed to remove audit map. lockName=$lockName, permitId=$permitId" } }
        }
    }

    private fun Throwable.unwrapCompletionCause(): Throwable =
        (this as? CompletionException)?.cause ?: this

    private fun Throwable.toActionFailedResult(): LeaderRunResult.ActionFailed {
        val cause = unwrapCompletionCause()
        if (cause is CancellationException) {
            throw cause
        }
        return LeaderRunResult.ActionFailed(cause)
    }

    private fun Throwable.asCompletionException(): CompletionException =
        this as? CompletionException ?: CompletionException(this)

    private fun releaseOrExtend(
        semaphore: RPermitExpirableSemaphore,
        permitId: String,
        startedAtNanos: Long,
        lockName: String,
    ) {
        try {
            val remainingMs = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime).inWholeMilliseconds
            if (remainingMs > 0) {
                semaphore.updateLeaseTime(permitId, remainingMs, TimeUnit.MILLISECONDS)
                log.debug {
                    "minLease 유지를 위해 leaseTime 갱신. lockName=$lockName, permitId=$permitId, remainingMs=$remainingMs"
                }
            } else {
                semaphore.release(permitId)
                log.debug { "슬롯 즉시 반납. lockName=$lockName, permitId=$permitId" }
            }
        } catch (e: Throwable) {
            log.warn(e) { "Failed to release/extend permit. lockName=$lockName, permitId=$permitId" }
        }
    }

    private fun <T> releaseAndPropagate(
        semaphore: RPermitExpirableSemaphore,
        permitId: String,
        startedAtNanos: Long,
        lockName: String,
        error: Throwable?,
        value: T?,
    ): CompletableFuture<T?> =
        releaseOrExtendAsync(semaphore, permitId, startedAtNanos, lockName)
            .handle { _, releaseError ->
                if (releaseError != null) {
                    log.warn(releaseError) { "Failed to release/extend permit. lockName=$lockName, permitId=$permitId" }
                }
                Unit
            }
            .thenCompose {
                if (error != null) {
                    CompletableFuture.failedFuture(error)
                } else {
                    CompletableFuture.completedFuture(value)
                }
            }

    private fun releaseOrExtendAsync(
        semaphore: RPermitExpirableSemaphore,
        permitId: String,
        startedAtNanos: Long,
        lockName: String,
    ): CompletableFuture<*> {
        val remainingMs = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime).inWholeMilliseconds
        return if (remainingMs > 0) {
            semaphore.updateLeaseTimeAsync(permitId, remainingMs, TimeUnit.MILLISECONDS).toCompletableFuture()
        } else {
            semaphore.releaseAsync(permitId).toCompletableFuture()
        }
    }
}

/**
 * Performs an action via multi-leader election using a Redisson distributed semaphore.
 *
 * ```kotlin
 * val client: RedissonClient = ...
 * val options = LeaderGroupElectionOptions(maxLeaders = 3)
 * val result: Int = client.runIfLeaderGroup("batch-job", options) {
 *     // Up to 3 processes run concurrently
 *     42
 * }
 * ```
 */
inline fun <T> RedissonClient.runIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    lockName.requireNotBlank("lockName")
    return RedissonLeaderGroupElector(this, options)
        .runIfLeader(lockName) {
            action()
        }
}
