package io.bluetape4k.leader.lettuce

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
import io.bluetape4k.leader.lettuce.internal.LettuceBackendErrorClassifier
import io.bluetape4k.leader.lettuce.internal.LettuceSuspendSlotExtendDelegate
import io.bluetape4k.leader.lettuce.semaphore.LettuceSlotTokenGroup
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 주어진 Redis 연결과 그룹 선출 정책으로 suspend slot-token 기반 elector를 생성합니다. */
fun StatefulRedisConnection<String, String>.suspendLeaderGroupElector(
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
): LettuceSuspendLeaderGroupElector =
    LettuceSuspendLeaderGroupElector(this, options)


/**
 * `LettuceSuspendLeaderGroupElector`는 Redis Lettuce의 suspend 그룹 선출을 제공하며 `maxLeaders` 슬롯을 acquire·extend·release합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property connection lockName별 Redis sorted-set·metadata hash를 조회하고 갱신하는 연결입니다.
 * @property options 동시에 허용할 `maxLeaders`, 대기·lease·최소 lease 기간을 정하는 그룹 정책입니다.
 */
class LettuceSuspendLeaderGroupElector(
    private val connection: StatefulRedisConnection<String, String>,
    val options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
): SuspendLeaderGroupElector, LeaderBackendDiagnosticsProvider by LettuceLeaderBackendDiagnostics(connection) {

    companion object: KLogging() {
        internal const val LETTUCE_SUSPEND_GROUP_FACTORY_BEAN_NAME = "lettuce-suspend-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(LettuceBackendErrorClassifier)
    }

    override val maxLeaders: Int = options.maxLeaders

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

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        runImpl(lockName, auditLeaderId = null, action)

    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        runImpl(slot.lockName, auditLeaderId = slot.leaderId, action)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
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

    private suspend fun <T> runImpl(lockName: String, auditLeaderId: String?, action: suspend () -> T): T? {
        val slotGroup = getSlotGroup(lockName)

        val token = slotGroup.tryAcquireSuspending(options.waitTime, options.leaseTime, auditLeaderId ?: "")
        if (token == null) {
            log.debug { "리더 선출 실패 (슬롯 없음, suspend): lockName=$lockName" }
            return null
        }
        // Codex P2: acquire 성공 후 startedAtNanos 캡처
        val startedAtNanos = System.nanoTime()
        val delegate: SuspendExtendDelegate = LettuceSuspendSlotExtendDelegate(slotGroup, token)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = LETTUCE_SUSPEND_GROUP_FACTORY_BEAN_NAME,
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
        log.debug { "리더 선출 성공 (suspend): lockName=$lockName, token=$token" }

        try {
            return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
        } finally {
            releaseSlotAfterRun(slotGroup, token, startedAtNanos, watchdog, lockName)
        }
    }

    /**
     * 취소 중에도 watchdog와 슬롯을 정리하고, release 취소만 caller에게 재전파합니다.
     *
     * 이 helper를 finally 바깥에 두어 cleanup 예외가 action 예외를 덮을 수 있다는
     * `ThrowingExceptionFromFinally` 오탐을 피하면서 기존 취소 계약을 유지합니다.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun releaseSlotAfterRun(
        slotGroup: LettuceSlotTokenGroup,
        token: String,
        startedAtNanos: Long,
        watchdog: AutoCloseable,
        lockName: String,
    ) {
        // NonCancellable: 코루틴 취소 시에도 슬롯 반납이 중단되지 않도록 보호
        withContext(NonCancellable) {
            LeaderLeaseAutoExtender.closeSuspend(watchdog)
            try {
                val remainingMs = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime).inWholeMilliseconds
                slotGroup.releaseSuspending(token, remainingMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn(e) { "Failed to release slot. lockName=$lockName, token=$token" }
            }
        }
    }
}
