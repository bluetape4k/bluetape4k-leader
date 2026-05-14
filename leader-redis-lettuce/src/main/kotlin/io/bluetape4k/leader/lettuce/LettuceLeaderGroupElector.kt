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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * [StatefulRedisConnection]에서 [LettuceLeaderGroupElector] 인스턴스를 생성합니다.
 *
 * ```kotlin
 * val options = LeaderGroupElectionOptions(maxLeaders = 3)
 * val election = connection.leaderGroupElection(options)
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * @param options    리더 선출 옵션 (기본값: [LeaderGroupElectionOptions.Default])
 * @return [LettuceLeaderGroupElector] 인스턴스
 */
fun StatefulRedisConnection<String, String>.leaderGroupElection(
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
): LettuceLeaderGroupElector =
    LettuceLeaderGroupElector(this, options)


/**
 * Lettuce Redis 클라이언트 기반의 복수 리더 선출 구현체입니다.
 *
 * ## 동작/계약 (T7 PR 2)
 *
 * - 내부적으로 [LettuceSlotTokenGroup] (ZSET + Lua) 을 사용하여 slot-token 모델로 동작합니다.
 * - acquire 후 [LettuceSlotExtendDelegate] 를 생성하여 [LeaderLockHandle.Real] + watchdog 와 동일 reference 공유 (AC-15).
 * - aspect 가 `LockExtender.extendActiveLock` 호출 시 동일 delegate 를 통해 server-side TIME Lua 실행 (AC-16).
 * - `LockStateHolder` + `LeaderLockHandleCapture` (via AopScopeAccess) 양쪽에 handle 을 push 하여 aspect 가 reentrant peek + capture poll 모두 가능.
 *
 * ```kotlin
 * val options = LeaderGroupElectionOptions(maxLeaders = 3, minLeaseTime = 1.seconds)
 * val election = LettuceLeaderGroupElector(connection, options)
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * @param connection Lettuce [StatefulRedisConnection] (StringCodec 기반)
 * @param options    리더 선출 옵션 (maxLeaders, waitTime, leaseTime, minLeaseTime)
 */
class LettuceLeaderGroupElector(
    private val connection: StatefulRedisConnection<String, String>,
    val options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
): LeaderGroupElector {

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
    ): CompletableFuture<T?> {
        val slotGroup = getSlotGroup(lockName)

        return slotGroup.tryAcquireAsync(options.waitTime, options.leaseTime).thenComposeAsync({ token ->
            if (token == null) {
                log.debug { "리더 선출 실패 (슬롯 없음, async): lockName=$lockName" }
                CompletableFuture.completedFuture<T?>(null)
            } else {
                // Codex P2: acquire 성공 후 startedAtNanos 캡처
                val startedAtNanos = System.nanoTime()
                log.debug { "리더 선출 성공 (async): lockName=$lockName, token=$token" }

                // Codex P2-2: action 결과(성공/실패)와 무관하게 release 완료까지 대기한 뒤 outer future 를 complete.
                val actionFuture: CompletableFuture<T> = try {
                    action()
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
                Unit
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
