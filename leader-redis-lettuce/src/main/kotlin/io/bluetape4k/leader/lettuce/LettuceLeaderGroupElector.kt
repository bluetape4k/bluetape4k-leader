package io.bluetape4k.leader.lettuce

import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.AsyncLifecycle
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.lettuce.internal.LettuceBackendErrorClassifier
import io.bluetape4k.leader.lettuce.internal.LettuceSlotExtendDelegate
import io.bluetape4k.leader.lettuce.semaphore.LettuceSlotTokenGroup
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.api.StatefulRedisConnection
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 주어진 Redis 연결과 그룹 선출 정책으로 slot-token 기반 `LettuceLeaderGroupElector`를 생성합니다. */
fun StatefulRedisConnection<String, String>.leaderGroupElection(
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
): LettuceLeaderGroupElector =
    LettuceLeaderGroupElector(this, options)


/**
 * `LettuceLeaderGroupElector`는 Redis Lettuce의 blocking 그룹 선출을 제공하며 `maxLeaders` 슬롯을 acquire·extend·release합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property connection lockName별 Redis sorted-set·metadata hash를 조회하고 갱신하는 연결입니다.
 * @property options 동시에 허용할 `maxLeaders`, 대기·lease·최소 lease 기간을 정하는 그룹 정책입니다.
 */
// Group elector는 sync/async, 상태 조회, lease lifecycle, diagnostics 계약을 함께 구현합니다.
@Suppress("TooManyFunctions")
class LettuceLeaderGroupElector(
    private val connection: StatefulRedisConnection<String, String>,
    val options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
): LeaderGroupElector, LeaderBackendDiagnosticsProvider by LettuceLeaderBackendDiagnostics(connection) {

    companion object: KLogging() {
        internal const val LETTUCE_GROUP_FACTORY_BEAN_NAME = "lettuce-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(LettuceBackendErrorClassifier)
    }

    override val maxLeaders: Int = options.maxLeaders

    // lockName 별 slot-token group 을 1회만 생성하여 재사용합니다.
    private val slotGroups = ConcurrentHashMap<String, LettuceSlotTokenGroup>()

    private fun getSlotGroup(lockName: String): LettuceSlotTokenGroup {
        lockName.requireNotBlank("lockName")
        return slotGroups.computeIfAbsent(lockName) {
            LettuceSlotTokenGroup(connection, it, maxLeaders)
        }
    }

    override fun activeCount(lockName: String): Int = getSlotGroup(lockName).activeCount()

    override fun availableSlots(lockName: String): Int = getSlotGroup(lockName).availableSlots()

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        runImpl(lockName, auditLeaderId = null, action)

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        runImpl(slot.lockName, auditLeaderId = slot.leaderId, action)

    @Suppress("ThrowsCount")
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

    private inline fun <T> runImpl(
        lockName: String,
        auditLeaderId: String?,
        crossinline action: () -> T,
    ): T? {
        val slotGroup = getSlotGroup(lockName)

        val token = slotGroup.tryAcquire(options.waitTime, options.leaseTime, auditLeaderId ?: "")
        if (token == null) {
            log.debug { "리더 선출 실패 (슬롯 없음): lockName=$lockName" }
            return null
        }
        // Codex P2: acquire 성공 후 startedAtNanos 캡처. acquire 전 캡처 시 waitTime 이 minLease 에서 차감.
        val startedAtNanos = System.nanoTime()
        val delegate = LettuceSlotExtendDelegate(slotGroup, token)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = LETTUCE_GROUP_FACTORY_BEAN_NAME,
            groupParams = LockIdentity.GroupParams(maxLeaders),
        )
        val handle = LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = startedAtNanos,
            slotId = token,
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId,
        )

        // Group elector: autoExtend 옵션 부재 — caller 가 LockExtender 로 명시적 연장. watchdog disabled.
        val watchdog = LeaderLeaseAutoExtender.start(false, options.leaseTime, delegate, ERROR_CLASSIFIER)

        log.debug { "리더 선출 성공: lockName=$lockName, token=$token" }
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
            val remainingMs = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime).inWholeMilliseconds
            try {
                slotGroup.release(token, remainingMs)
            } catch (e: Throwable) {
                log.warn(e) { "Failed to release slot. lockName=$lockName, token=$token" }
            }
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
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        return LeaderFutureBridge.map(runAsyncIfLeader(slot, executor) {
            elected.set(true)
            cancellationRelay.invoke(action)
        }, cancellationRelay) { value, failure ->
            when {
                failure != null && elected.get() -> failure.toActionFailedResult()
                failure != null -> throw failure.asCompletionException()
                elected.get()   -> LeaderRunResult.Elected(value, leaderId = slot.leaderId)
                else            -> LeaderRunResult.Skipped
            }
        }
    }

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    private inline fun <T> runAsyncImpl(
        lockName: String,
        auditLeaderId: String?,
        executor: Executor,
        crossinline action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val slotGroup = getSlotGroup(lockName)

        val acquiredToken = AtomicReference<String?>()
        val acquiredAtNanos = AtomicLong()
        val lifecycle = AtomicReference(AsyncLifecycle.WAITING)
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        val releaseAfterRejection: (Throwable) -> CompletableFuture<T?> = { failure ->
            val token = acquiredToken.get()
            if (
                token != null &&
                lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.CLEANUP)
            ) {
                releaseAndPropagate(slotGroup, lockName, token, acquiredAtNanos.get(), failure, null)
            } else {
                failedCompletableFutureOf(failure)
            }
        }
        val acquisitionFuture = slotGroup
            .tryAcquireAsync(options.waitTime, options.leaseTime, auditLeaderId ?: "")
            .thenApply { token ->
                if (token != null) {
                    acquiredAtNanos.set(System.nanoTime())
                    acquiredToken.set(token)
                }
                token
            }
        val pipelineFuture = acquisitionFuture.thenComposeAsync({ token ->
            if (token == null) {
                log.debug { "리더 선출 실패 (슬롯 없음, async): lockName=$lockName" }
                completableFutureOf(null)
            } else {
                val startedAtNanos = acquiredAtNanos.get()
                if (!lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.STARTED)) {
                    failedCompletableFutureOf(
                        CancellationException("leader group action was cancelled before start"),
                    )
                } else try {
                    runAcquiredAsync(
                        slotGroup,
                        lockName,
                        token,
                        auditLeaderId,
                        startedAtNanos,
                        cancellationRelay,
                        action,
                    )
                } catch (error: Throwable) {
                    releaseAndPropagate(slotGroup, lockName, token, startedAtNanos, error, null)
                }
            }
        }, executor)
        acquisitionFuture.whenComplete { token, _ ->
            if (token != null && pipelineFuture.isCancelled) {
                releaseAfterRejection(CancellationException("leader group result future was cancelled before action"))
            }
        }
        return LeaderFutureBridge.flatMap(pipelineFuture, cancellationRelay) { value, failure ->
            if (failure != null) {
                releaseAfterRejection(failure.unwrapCompletionCause())
            } else {
                completableFutureOf(value)
            }
        }
    }

    private inline fun <T> runAcquiredAsync(
        slotGroup: LettuceSlotTokenGroup,
        lockName: String,
        token: String,
        auditLeaderId: String?,
        startedAtNanos: Long,
        cancellationRelay: LeaderFutureBridge.CancellationRelay,
        crossinline action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val delegate = LettuceSlotExtendDelegate(slotGroup, token)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = LETTUCE_GROUP_FACTORY_BEAN_NAME,
            groupParams = LockIdentity.GroupParams(maxLeaders),
        )
        val handle = LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = startedAtNanos,
            slotId = token,
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId,
        )
        log.debug { "리더 선출 성공 (async): lockName=$lockName, token=$token" }
        val actionFuture = runCatching {
            cancellationRelay.invoke {
                AopScopeAccess.withPushedSync(handle) {
                    AopScopeAccess.setCapture(handle)
                    try {
                        action()
                    } finally {
                        AopScopeAccess.clearCapture()
                    }
                }
            }
        }.getOrElse { error ->
            return releaseAndPropagate(slotGroup, lockName, token, startedAtNanos, error, null)
        }
        return actionFuture
            .handle<Pair<T?, Throwable?>> { value, error -> Pair(value, error) }
            .thenCompose { (value, error) ->
                releaseAndPropagate(slotGroup, lockName, token, startedAtNanos, error, value)
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

    private fun <T> releaseAndPropagate(
        slotGroup: LettuceSlotTokenGroup,
        lockName: String,
        token: String,
        startedAtNanos: Long,
        error: Throwable?,
        value: T?,
    ): CompletableFuture<T?> {
        val remainingMs = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime).inWholeMilliseconds
        return slotGroup.releaseAsync(token, remainingMs)
            .exceptionally { releaseError ->
                log.warn(releaseError) { "Failed to release slot. lockName=$lockName, token=$token" }
            }
            .thenCompose {
                if (error != null) {
                    failedCompletableFutureOf(error)
                } else {
                    completableFutureOf<T?>(value)
                }
            }
    }
}
