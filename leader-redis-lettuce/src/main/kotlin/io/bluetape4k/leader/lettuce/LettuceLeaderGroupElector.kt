package io.bluetape4k.leader.lettuce

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
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.lettuce.internal.LettuceBackendErrorClassifier
import io.bluetape4k.leader.lettuce.internal.LettuceSlotExtendDelegate
import io.bluetape4k.leader.lettuce.semaphore.LettuceSlotTokenGroup
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
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

/**
 * `StatefulRedisConnection` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun StatefulRedisConnection<String, String>.leaderGroupElection(
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
): LettuceLeaderGroupElector =
    LettuceLeaderGroupElector(this, options)


/**
 * `LettuceLeaderGroupElector`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property connection Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
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

    private fun <T> runImpl(lockName: String, auditLeaderId: String?, action: () -> T): T? {
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
        return LeaderFutureBridge.map(runAsyncIfLeader(slot, executor) {
            elected.set(true)
            action()
        }) { value, failure ->
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
        val slotGroup = getSlotGroup(lockName)

        return slotGroup.tryAcquireAsync(options.waitTime, options.leaseTime, auditLeaderId ?: "").thenComposeAsync({ token ->
            if (token == null) {
                log.debug { "리더 선출 실패 (슬롯 없음, async): lockName=$lockName" }
                CompletableFuture.completedFuture<T?>(null)
            } else {
                // Codex P2: acquire 성공 후 startedAtNanos 캡처
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
                log.debug { "리더 선출 성공 (async): lockName=$lockName, token=$token" }

                // Codex P2-2: action 결과(성공/실패)와 무관하게 release 완료까지 대기한 뒤 outer future 를 complete.
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
                    return@thenComposeAsync releaseAndPropagate<T>(slotGroup, lockName, token, startedAtNanos, e, null)
                }

                actionFuture.handle<Pair<T?, Throwable?>> { value, error ->
                    Pair(value, error)
                }.thenCompose { (value, error) ->
                    releaseAndPropagate<T>(slotGroup, lockName, token, startedAtNanos, error, value)
                }
            }
        }, executor)
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
                    CompletableFuture.failedFuture(error)
                } else {
                    CompletableFuture.completedFuture<T?>(value)
                }
            }
    }
}
