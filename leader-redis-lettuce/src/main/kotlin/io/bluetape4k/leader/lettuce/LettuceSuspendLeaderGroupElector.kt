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
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
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

/**
 * [StatefulRedisConnection]에서 [LettuceSuspendLeaderGroupElector] 인스턴스를 생성합니다.
 *
 * ```kotlin
 * val election = connection.suspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-job") { processChunkSuspend() }
 * ```
 *
 * @param options    리더 선출 옵션 (기본값: [LeaderGroupElectionOptions.Default])
 * @return [LettuceSuspendLeaderGroupElector] 인스턴스
 */
fun StatefulRedisConnection<String, String>.suspendLeaderGroupElector(
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
): LettuceSuspendLeaderGroupElector =
    LettuceSuspendLeaderGroupElector(this, options)


/**
 * Lettuce Redis 기반의 코루틴 복수 리더 선출 구현체입니다.
 *
 * ## 동작/계약 (T7 PR 2)
 *
 * - 내부적으로 [LettuceSlotTokenGroup] (ZSET + Lua, server-side TIME) 사용.
 * - acquire 후 [LettuceSuspendSlotExtendDelegate] 를 생성하여 [LeaderLockHandle.Real] + watchdog 와 동일 reference 공유 (AC-15).
 * - `withContext(AopScopeAccess.createLockHandleElement(handle))` 로
 *   coroutineContext 에 handle 전파.
 * - 코루틴 취소 시에도 `withContext(NonCancellable)` 안에서 release 가 보장됩니다.
 * - `CancellationException` 은 항상 re-throw 합니다.
 *
 * ```kotlin
 * val options = LeaderGroupElectionOptions(maxLeaders = 3, minLeaseTime = 1.seconds)
 * val election = LettuceSuspendLeaderGroupElector(connection, options)
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * @param connection Lettuce [StatefulRedisConnection] (StringCodec 기반)
 * @param options    리더 선출 옵션 (maxLeaders, waitTime, leaseTime, minLeaseTime)
 */
class LettuceSuspendLeaderGroupElector(
    private val connection: StatefulRedisConnection<String, String>,
    val options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
): SuspendLeaderGroupElector {

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

    override suspend fun <T> runIfLeaderResultSuspend(slot: LeaderSlot, action: suspend () -> T): LeaderRunResult<T> {
        var elected = false
        val value = runImpl(slot.lockName, auditLeaderId = slot.leaderId) { elected = true; action() }
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
        val delegate = LettuceSuspendSlotExtendDelegate(slotGroup, token)
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
            // NonCancellable: 코루틴 취소 시에도 슬롯 반납이 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog.close()
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
}
