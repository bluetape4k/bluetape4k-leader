package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.lettuce.internal.LettuceBackendErrorClassifier
import io.bluetape4k.leader.lettuce.internal.LettuceSuspendLockExtendDelegate
import io.bluetape4k.leader.lettuce.lock.LettuceSuspendLock
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.api.StatefulRedisConnection
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * [StatefulRedisConnection]에서 [LettuceSuspendLeaderElector] 인스턴스를 생성합니다.
 *
 * ```kotlin
 * val election = connection.suspendLeaderElector()
 * val result = election.runIfLeader("daily-job") { "done" }
 * ```
 *
 * @param options 리더 선출 옵션 (기본값: [LeaderElectionOptions.Default])
 * @return [LettuceSuspendLeaderElector] 인스턴스
 */
fun StatefulRedisConnection<String, String>.suspendLeaderElector(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): LettuceSuspendLeaderElector =
    LettuceSuspendLeaderElector(this, options)


/**
 * Lettuce Redis 클라이언트를 이용한 코루틴 기반 리더 선출 구현체입니다.
 *
 * [LettuceSuspendLock]을 사용하여 비동기적으로 리더를 선출합니다.
 *
 * ## 동작/계약 (T7 PR 2)
 *
 * - acquire 후 [LettuceSuspendLockExtendDelegate] 를 생성하여 [LeaderLockHandle.Real] + watchdog 와 공유합니다.
 * - aspect 의 `LockExtenderSuspend.extendActiveLockSuspend` 는 동일 delegate reference 를 사용합니다 (AC-15).
 * - watchdog 은 [LeaderLeaseAutoExtender.start] 새 시그니처를 사용해 R2 watchdog skip semantics 활성화.
 * - `withContext(AopScopeAccess.createLockHandleElement(handle))` 로 coroutineContext 에 handle 전파.
 *
 * ```kotlin
 * val election = LettuceSuspendLeaderElector(connection)
 * val result = election.runIfLeader("daily-job") { "done" }
 * ```
 *
 * @param connection Lettuce [StatefulRedisConnection] (StringCodec 기반)
 * @param options    리더 선출 옵션 (waitTime, leaseTime)
 */
class LettuceSuspendLeaderElector(
    private val connection: StatefulRedisConnection<String, String>,
    val options: LeaderElectionOptions = LeaderElectionOptions.Default,
    private val historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
): SuspendLeaderElector {

    companion object: KLogging() {
        internal const val LETTUCE_SUSPEND_FACTORY_BEAN_NAME = "lettuce-suspend-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(LettuceBackendErrorClassifier)
    }

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

    private suspend fun <T> runImpl(lockName: String, auditLeaderId: String?, action: suspend () -> T): T? {
        lockName.requireNotBlank("lockName")

        val lock = LettuceSuspendLock(connection, lockName, options.leaseTime)
        val acquired = lock.tryLock(options.waitTime, options.leaseTime)
        if (!acquired) {
            log.debug { "리더 선출 실패 (슬롯 없음, suspend): lockName=$lockName" }
            return null
        }
        val startedAt = Instant.now()
        val acquiredAtNanos = System.nanoTime()
        val token = lock.currentToken() ?: error("token missing after tryLock — lockName=$lockName")
        val delegate = LettuceSuspendLockExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = LETTUCE_SUSPEND_FACTORY_BEAN_NAME,
        )
        val handle = LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = acquiredAtNanos,
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId,
        )
        val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate, ERROR_CLASSIFIER)

        val record = historyRecorder?.let {
            LeaderLockHistoryRecord(
                lockName = lockName,
                token = token,
                kind = LockIdentity.AnnotationKind.SINGLE,
                acquiredAt = startedAt,
                lockedUntil = startedAt.plusMillis(options.leaseTime.inWholeMilliseconds),
            )
        }
        val key = record?.let { historyRecorder.recordAcquired(it) }
        val effectiveKey: LeaderHistoryKey? =
            key ?: record?.let { LeaderHistoryKey(lockName = lockName, token = token) }

        log.debug { "리더 선출 성공 (suspend): lockName=$lockName" }
        try {
            return try {
                val result = withContext(AopScopeAccess.createLockHandleElement(handle)) {
                    action()
                }
                val finishedAt = Instant.now()
                val durationMs = (System.nanoTime() - acquiredAtNanos) / 1_000_000L
                effectiveKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val finishedAt = Instant.now()
                val durationMs = (System.nanoTime() - acquiredAtNanos) / 1_000_000L
                effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, e) }
                throw e
            }
        } finally {
            // NonCancellable: 코루틴 취소 시에도 lease 정리가 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog.close()
                try {
                    if (lock.isHeldByCurrentInstance()) {
                        lock.unlock(options.minLeaseTime, acquiredAtNanos)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "Fail to release lock. lockName=$lockName" }
                }
            }
        }
    }
}
